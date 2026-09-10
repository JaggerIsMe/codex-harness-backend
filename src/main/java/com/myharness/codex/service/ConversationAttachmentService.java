package com.myharness.codex.service;

import com.myharness.codex.config.WorkspaceFileProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.security.DeviceAuthenticationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** Owns storage, authorization and attachment/message lifecycle. Call bind inside the Turn transaction. */
@Service
public class ConversationAttachmentService {
    private final WorkspaceFileService workspaceFiles;
    private final ConversationAttachmentMapper mapper;
    private final ConversationMapper conversations;
    private final ProjectMapper projects;
    private final AgentDeviceMapper devices;
    private final AuthorizationService access;
    private final DeviceAuthenticationService authentication;
    private final WorkspaceFileProperties properties;
    private final TransactionTemplate transactions;

    public ConversationAttachmentService(ConversationAttachmentMapper mapper, ConversationMapper conversations,
            ProjectMapper projects, AgentDeviceMapper devices, AuthorizationService access,
            DeviceAuthenticationService authentication, WorkspaceFileProperties properties, TransactionTemplate transactions, WorkspaceFileService workspaceFiles) {
        this.mapper=mapper; this.conversations=conversations; this.projects=projects; this.devices=devices;
        this.access=access; this.authentication=authentication; this.properties=properties; this.transactions=transactions;
        this.workspaceFiles=Objects.requireNonNull(workspaceFiles);
    }

    public AttachmentLimitsVO limits(Long pid, Long cid, Long uid) {
        ConversationPO c=owned(pid,cid,uid);
        AgentDevicePO device=devices.selectById(c.getDeviceId());
        return new AttachmentLimitsVO(properties.getMaxFileBytes(),properties.getMaxFiles(),properties.getMaxTotalBytes(),
                device!=null && Boolean.TRUE.equals(device.getConversationAttachments()) && Boolean.TRUE.equals(device.getWorkspaceFiles()));
    }

    public ConversationAttachmentVO upload(Long pid,Long cid,Long uid,MultipartFile file) throws IOException {
        owned(pid,cid,uid); access.requirePermission(uid,"turn:start");
        if(file==null || file.isEmpty() || file.getSize()>properties.getMaxFileBytes()) throw invalid("附件为空或超过单文件限制");
        String name=file.getOriginalFilename();
        if(name==null || name.isBlank() || name.length()>255 || name.chars().anyMatch(Character::isISOControl)) throw invalid("文件名无效");
        name=name.replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1);
        if(name.isBlank()) throw invalid("文件名无效");
        Path root=storageRoot();
        String key=UUID.randomUUID().toString(); Path target=root.resolve(key); Path temporary=Files.createTempFile(root,"upload-",".part");
        boolean stored=false;
        try {
            MessageDigest digest=digest(); long total=0;
            try(var input=file.getInputStream(); var output=Files.newOutputStream(temporary)) {
                byte[] buffer=new byte[8192]; int count;
                while((count=input.read(buffer))!=-1) {
                    total+=count; if(total>properties.getMaxFileBytes()) throw invalid("附件超过单文件限制");
                    digest.update(buffer,0,count); output.write(buffer,0,count);
                }
            }
            if(total==0) throw invalid("附件不能为空");
            ConversationAttachmentPO p=new ConversationAttachmentPO();
            p.setUserId(uid); p.setProjectId(pid); p.setConversationId(cid); p.setFileName(name); p.setStorageKey(key);
            p.setMediaType("application/octet-stream"); p.setSizeBytes(total); p.setSha256(HexFormat.of().formatHex(digest.digest()));
            p.setMediaType(detectMediaType(temporary));
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
            transactions.execute(tx -> {
                mapper.lockProject(pid);
                workspaceFiles.assertNoMutation(pid);
                conversations.lockConversation(cid);
                owned(pid,cid,uid);
                List<ConversationAttachmentPO> pending=mapper.pending(cid);
                if(pending.size()>=properties.getMaxFiles() || pending.stream().mapToLong(ConversationAttachmentPO::getSizeBytes).sum()+p.getSizeBytes()>properties.getMaxTotalBytes())
                    throw invalid("待发送附件数量或总大小超过限制，请先移除附件");
                mapper.insert(p);
                var operation=workspaceFiles.uploadAttachment(p);
                p.setWorkspacePath(p.getFileName());p.setWorkspaceOperationId(Long.valueOf(operation.id()));
                mapper.workspace(p.getId(),p.getWorkspacePath(),p.getWorkspaceOperationId());
                return null;
            });
            stored=true; return new ConversationAttachmentVO(p);
        } finally {
            Files.deleteIfExists(temporary);
            if(!stored) Files.deleteIfExists(target);
        }
    }

    public List<ConversationAttachmentVO> pending(Long pid,Long cid,Long uid) {
        owned(pid,cid,uid); return views(mapper.pending(cid));
    }

    public WorkspaceFileOperationVO download(Long pid,Long cid,Long aid,Long uid,String requestKey) {
        owned(pid,cid,uid);
        return transactions.execute(tx -> {
            mapper.lockProject(pid);
            owned(pid,cid,uid);
            workspaceFiles.assertNoMutation(pid);
            ConversationAttachmentPO attachment=mapper.find(cid,aid);
            if(attachment==null || !pid.equals(attachment.getProjectId()) || !uid.equals(attachment.getUserId())) throw missing();
            requireAvailable(attachment);
            workspaceFiles.requireUploaded(attachment);
            return workspaceFiles.download(pid,uid,new com.myharness.codex.entity.dto.WorkspaceFileRequestDTO(attachment.getWorkspacePath(),requestKey));
        });
    }

    public void delete(Long pid,Long cid,Long aid,Long uid) throws IOException {
        owned(pid,cid,uid); access.requirePermission(uid,"turn:start");
        ConversationAttachmentPO p=transactions.execute(tx -> {
            mapper.lockProject(pid);
            conversations.lockConversation(cid);
            ConversationAttachmentPO value=mapper.find(cid,aid);
            if(value==null) throw missing();
            if(mapper.markDeleted(aid)!=1) throw invalid("已发送的附件不能移除");
            return value;
        });
        workspaceFiles.detachedAttachment(p.getWorkspaceOperationId());
        Files.deleteIfExists(path(p)); mapper.purge(aid);
    }

    public List<ConversationAttachmentVO> bind(ConversationPO c,Long tid,List<Long> ids) {
        if(ids==null || ids.isEmpty()) return List.of();
        if(ids.size()>properties.getMaxFiles() || new HashSet<>(ids).size()!=ids.size()) throw invalid("附件数量超限或重复");
        List<ConversationAttachmentPO> values=new ArrayList<>(); long total=0;
        // Caller holds Project then Conversation; attachment rows follow in stable order.
        for(Long id:ids.stream().sorted().toList()) {
            ConversationAttachmentPO p=mapper.lock(id);
            if(p==null || !c.getId().equals(p.getConversationId()) || !c.getUserId().equals(p.getUserId())
                    || !c.getProjectId().equals(p.getProjectId()) || !List.of("PENDING","ATTACHED").contains(p.getStatus())) throw missing();
            total+=p.getSizeBytes(); if(p.getSizeBytes()>properties.getMaxFileBytes() || total>properties.getMaxTotalBytes()) throw invalid("附件总大小超限");
            requireAvailable(p);
            workspaceFiles.requireUploaded(p);
            values.add(p);
        }
        values.sort(Comparator.comparingInt(p -> ids.indexOf(p.getId())));
        for(int i=0;i<values.size();i++) {
            mapper.attach(values.get(i).getId());
            if(mapper.link(tid,values.get(i).getId(),i)!=1) throw new IllegalStateException("User Message missing for attachment binding");
        }
        return views(values);
    }

    public List<ConversationAttachmentVO> forTurn(Long tid){return views(mapper.forTurn(tid));}
    public void enrich(List<ConversationMessageVO> messages) {
        List<Long> ids=messages.stream().filter(m -> "USER".equals(m.getRole())).map(ConversationMessageVO::getId).toList();
        if(ids.isEmpty()) return;
        Map<Long,List<ConversationAttachmentVO>> grouped=new HashMap<>();
        for(ConversationMessageAttachmentPO p:mapper.forMessages(ids)) grouped.computeIfAbsent(p.getMessageId(),key -> new ArrayList<>()).add(new ConversationAttachmentVO(p));
        for(ConversationMessageVO m:messages) m.setAttachments(grouped.getOrDefault(m.getId(),List.of()));
    }

    public List<ConversationAttachmentVO> agentManifest(Long tid,String deviceCode,String authorization) {
        requireAgentTurn(tid,deviceCode,authorization);
        var attachments=mapper.forTurn(tid);
        attachments.forEach(ConversationAttachmentService::requireAvailable);
        return views(attachments);
    }

    private static void requireAvailable(ConversationAttachmentPO attachment) {
        if(!"AVAILABLE".equals(attachment.getWorkspaceLocationState()))
            throw new BusinessException(ErrorCode.CONFLICT,"MISSING".equals(attachment.getWorkspaceLocationState())
                    ? "附件文件已删除，请移除附件或重新上传" : "附件文件位置尚待核实，请等待文件操作结果");
    }

    private void requireAgentTurn(Long tid,String code,String auth) {
        AgentDevicePO device=authentication.authenticate(code,auth);
        ConversationTurnPO turn=conversations.selectTurn(tid);
        if(turn==null || !"CREATED".equals(turn.getStatus())) throw missing();
        ConversationPO c=conversations.selectConversation(turn.getConversationId());
        if(c==null || !device.getId().equals(c.getDeviceId()) || !"ACTIVE".equals(c.getStatus())) throw missing();
        owned(c.getProjectId(),c.getId(),c.getUserId()); access.requirePermission(c.getUserId(),"turn:start");
    }

    private ConversationPO owned(Long pid,Long cid,Long uid) {
        access.requirePermission(uid,"conversation:read");
        ConversationPO c=conversations.selectOwnedConversation(pid,cid,uid);
        ProjectPO p=projects.selectOwned(pid,uid);
        if(c==null || p==null || !"ACTIVE".equals(p.getStatus())) throw missing();
        access.requireDevice(uid,c.getDeviceId()); return c;
    }

    private Path storageRoot() throws IOException {Path p=Path.of(properties.getStorageDir()).toAbsolutePath().normalize(); Files.createDirectories(p); return p.toRealPath();}
    private Path path(ConversationAttachmentPO p) throws IOException {
        if(p.getStorageKey()==null || !p.getStorageKey().matches("[a-f0-9-]{36}")) throw missing();
        return storageRoot().resolve(p.getStorageKey());
    }
    private List<ConversationAttachmentVO> views(List<ConversationAttachmentPO> list){return list.stream().map(ConversationAttachmentVO::new).toList();}
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_REQUEST,message);}
    private static BusinessException missing(){return new BusinessException(ErrorCode.NOT_FOUND,"附件不存在或不可访问");}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}

    private static String detectMediaType(Path file) throws IOException {
        byte[] h;try(var input=Files.newInputStream(file)){h=input.readNBytes(12);}
        if(h.length>=8 && h[0]==(byte)137 && h[1]==80 && h[2]==78 && h[3]==71 && h[4]==13 && h[5]==10 && h[6]==26 && h[7]==10) return "image/png";
        if(h.length>=3 && h[0]==(byte)255 && h[1]==(byte)216 && h[2]==(byte)255) return "image/jpeg";
        String header=new String(h,java.nio.charset.StandardCharsets.ISO_8859_1);
        if(header.startsWith("GIF87a") || header.startsWith("GIF89a")) return "image/gif";
        if(header.startsWith("RIFF") && header.length()>=12 && header.substring(8,12).equals("WEBP")) return "image/webp";
        return "application/octet-stream";
    }

    @Scheduled(fixedDelayString="${harness.workspace-files.cleanup-ms:3600000}")
    public void cleanup() {
        for(ConversationAttachmentPO p:mapper.expired(LocalDateTime.now().minusHours(24))) {
            try {
                boolean deleted=Boolean.TRUE.equals(transactions.execute(tx -> {
                    mapper.lockProject(p.getProjectId());
                    conversations.lockConversation(p.getConversationId());
                    ConversationAttachmentPO current=mapper.lock(p.getId());
                    return current!=null && ("DELETED".equals(current.getStatus()) || mapper.markDeleted(p.getId())==1);
                }));
                if(deleted) {workspaceFiles.detachedAttachment(p.getWorkspaceOperationId());Files.deleteIfExists(path(p)); mapper.purge(p.getId());}
            } catch(Exception e) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Attachment cleanup will retry {}",p.getId(),e);}
        }
    }
}
