package com.myharness.codex.service;

import com.myharness.codex.config.ArtifactProperties;
import com.myharness.codex.entity.dto.PublishArtifactDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** Owns immutable deliveries, authorization, idempotent publication and recovery. */
@Service
public class ConversationArtifactService {
    private final ConversationArtifactMapper artifacts;
    private final ConversationMapper conversations;
    private final ProjectMapper projects;
    private final AuthorizationService access;
    private final DeviceAuthenticationService authentication;
    private final ArtifactProperties properties;
    private final TransactionTemplate transactions;
    private final ClientEventWebSocketHandler events;

    public ConversationArtifactService(ConversationArtifactMapper artifacts, ConversationMapper conversations,
            ProjectMapper projects, AuthorizationService access, DeviceAuthenticationService authentication,
            ArtifactProperties properties, TransactionTemplate transactions, ClientEventWebSocketHandler events) {
        this.artifacts=artifacts; this.conversations=conversations; this.projects=projects;
        this.access=access; this.authentication=authentication; this.properties=properties;
        this.transactions=transactions; this.events=events;
    }

    public ConversationArtifactVO register(Long tid,String code,String auth,PublishArtifactDTO input) {
        ConversationPO c=agentTurn(tid,code,auth);
        validate(input);
        boolean[] created={false};
        ConversationArtifactPO p=transactions.execute(tx -> {
            conversations.lockConversation(c.getId());
            agentTurn(tid,code,auth);
            ConversationArtifactPO existing=artifacts.findKey(tid,input.artifactKey());
            if(existing!=null) {
                if(!existing.getFileName().equals(input.fileName()) || existing.getSizeBytes()!=input.sizeBytes()
                        || !existing.getSha256().equals(input.sha256())) throw conflict("同一产物不能替换内容");
                return existing;
            }
            List<ConversationArtifactPO> rows=artifacts.forTurn(tid);
            long total=rows.stream().mapToLong(ConversationArtifactPO::getSizeBytes).sum();
            if(rows.size()>=properties.getMaxFiles() || input.sizeBytes()>properties.getMaxTotalBytes()-total)
                throw invalid("本轮产物数量或总大小超限");
            ConversationArtifactPO value=new ConversationArtifactPO();
            value.setUserId(c.getUserId()); value.setProjectId(c.getProjectId()); value.setConversationId(c.getId());
            value.setTurnId(tid); value.setDeviceId(c.getDeviceId()); value.setArtifactKey(input.artifactKey());
            value.setFileName(input.fileName()); value.setSizeBytes(input.sizeBytes()); value.setSha256(input.sha256());
            value.setStorageKey(UUID.randomUUID().toString()); value.setMediaType("application/octet-stream");
            value.setStatus("UPLOADING"); artifacts.insert(value); created[0]=true; return value;
        });
        if(created[0]) changed(p); return new ConversationArtifactVO(p);
    }

    public ConversationArtifactVO agentStatus(Long tid,Long aid,String code,String auth) {
        agentTurn(tid,code,auth); return new ConversationArtifactVO(inTurn(tid,aid));
    }

    public ConversationArtifactVO upload(Long tid,Long aid,String code,String auth,InputStream input) throws IOException {
        agentTurn(tid,code,auth);
        ConversationArtifactPO p=inTurn(tid,aid);
        if("READY".equals(p.getStatus())) return new ConversationArtifactVO(p);
        if(!"UPLOADING".equals(p.getStatus())) throw conflict("请先重试产物上传");
        Path root=root(); Path temporary=Files.createTempFile(root,"upload-",".part");
        try {
            MessageDigest digest=digest(); long total=0;
            try(var output=Files.newOutputStream(temporary)) {
                byte[] bytes=new byte[8192]; int count;
                while((count=input.read(bytes))!=-1) {
                    total+=count;
                    if(total>p.getSizeBytes() || total>properties.getMaxFileBytes()) throw invalid("产物大小超限");
                    digest.update(bytes,0,count); output.write(bytes,0,count);
                }
            }
            if(total!=p.getSizeBytes() || !HexFormat.of().formatHex(digest.digest()).equals(p.getSha256()))
                throw invalid("产物大小或 SHA-256 校验失败");
            ConversationArtifactPO saved=transactions.execute(tx -> {
                // Files are read outside the transaction; reauthorize before publishing bytes.
                agentTurn(tid,code,auth);
                ConversationArtifactPO current=artifacts.lock(aid);
                if("READY".equals(current.getStatus())) return current;
                if(!"UPLOADING".equals(current.getStatus())) throw conflict("上传已超时，请重试");
                try {
                    Files.move(temporary,path(current),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } catch(IOException e) {throw new UncheckedIOException(e);}
                artifacts.ready(aid); current.setStatus("READY"); current.setErrorMessage(null); return current;
            });
            changed(saved); return new ConversationArtifactVO(saved);
        } catch(IOException | RuntimeException e) {
            if(artifacts.fail(aid,"上传失败，请重试；源设备需在线")==1) {
                p.setStatus("FAILED"); p.setErrorMessage("上传失败，请重试；源设备需在线"); changed(p);
            }
            throw e;
        } finally {Files.deleteIfExists(temporary);}
    }

    public void agentFailed(Long tid,Long aid,String code,String auth) {
        agentTurn(tid,code,auth); ConversationArtifactPO p=inTurn(tid,aid);
        if(artifacts.fail(aid,"上传失败，请重试；源设备需在线")==1) changed(p);
    }

    public List<ConversationArtifactVO> list(Long pid,Long cid,Long uid) {
        owned(pid,cid,uid); return artifacts.forConversation(cid).stream().map(ConversationArtifactVO::new).toList();
    }

    public ConversationArtifactVO retry(Long pid,Long cid,Long aid,Long uid) {
        owned(pid,cid,uid); access.requirePermission(uid,"turn:start");
        ConversationArtifactPO p=transactions.execute(tx -> {
            ConversationArtifactPO current=artifacts.lock(aid);
            if(current==null || !cid.equals(current.getConversationId())) throw missing();
            owned(pid,cid,uid);
            if(artifacts.retry(aid)==1) {current.setStatus("UPLOADING");current.setErrorMessage(null);}
            return current;
        });
        changed(p); return new ConversationArtifactVO(p);
    }

    public AttachmentFileVO download(Long pid,Long cid,Long aid,Long uid) throws IOException {
        owned(pid,cid,uid);
        ConversationArtifactPO p=artifacts.find(aid);
        if(p==null || !cid.equals(p.getConversationId())) throw missing();
        if(!"READY".equals(p.getStatus())) throw conflict("产物尚未准备好");
        Path file=path(p);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file)
                || Files.size(file)!=p.getSizeBytes()) throw missing();
        return new AttachmentFileVO(new FileSystemResource(file),p.getFileName(),p.getSizeBytes());
    }

    private ConversationPO agentTurn(Long tid,String code,String auth) {
        AgentDevicePO device=authentication.authenticate(code,auth);
        ConversationTurnPO turn=conversations.selectTurn(tid);
        // A completed Agent can reconnect after the Server marked its Turn FAILED on disconnect.
        if(turn==null || !Set.of("RUNNING","COMPLETED","FAILED").contains(turn.getStatus())) throw missing();
        ConversationPO c=conversations.selectConversation(turn.getConversationId());
        if(c==null || !device.getId().equals(c.getDeviceId()) || !"ACTIVE".equals(c.getStatus())) throw missing();
        owned(c.getProjectId(),c.getId(),c.getUserId()); access.requirePermission(c.getUserId(),"turn:start"); return c;
    }

    private ConversationPO owned(Long pid,Long cid,Long uid) {
        access.requirePermission(uid,"conversation:read");
        ConversationPO c=conversations.selectOwnedConversation(pid,cid,uid);
        ProjectPO p=projects.selectOwned(pid,uid);
        if(c==null || p==null || !"ACTIVE".equals(p.getStatus())) throw missing();
        access.requireDevice(uid,c.getDeviceId()); return c;
    }
    private ConversationArtifactPO inTurn(Long tid,Long aid) {
        ConversationArtifactPO p=artifacts.find(aid);
        if(p==null || !tid.equals(p.getTurnId())) throw missing(); return p;
    }
    private void validate(PublishArtifactDTO p) {
        if(p==null || p.artifactKey()==null || !p.artifactKey().matches("[a-zA-Z0-9_-]{1,64}")
                || p.fileName()==null || p.fileName().isBlank() || p.fileName().length()>255
                || p.fileName().matches(".*[\\\\/:].*") || p.fileName().chars().anyMatch(Character::isISOControl)
                || Set.of(".","..").contains(p.fileName()) || p.sizeBytes()<0 || p.sizeBytes()>properties.getMaxFileBytes()
                || p.sha256()==null || !p.sha256().matches("[a-f0-9]{64}")) throw invalid("产物元数据无效或超过限制");
    }
    private Path root() throws IOException {
        Path p=Path.of(properties.getStorageDir()).toAbsolutePath().normalize(); Files.createDirectories(p); return p.toRealPath();
    }
    private Path path(ConversationArtifactPO p) throws IOException {
        if(p.getStorageKey()==null || !p.getStorageKey().matches("[a-f0-9-]{36}")) throw missing();
        return root().resolve(p.getStorageKey());
    }
    private void changed(ConversationArtifactPO p) {
        // Publication is durable; a disconnected browser recovers by querying the list.
        try {events.sendToUser(p.getUserId(),new ArtifactChangedVO("ARTIFACT_CHANGED",p.getDeviceId(),
                new ArtifactChangedVO.Payload(p.getConversationId().toString(),p.getTurnId().toString())));}
        catch(RuntimeException e) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Artifact notification will recover on refresh: {}",p.getId());}
    }
    @Scheduled(fixedDelayString="${harness.artifacts.cleanup-ms:60000}")
    public void recoverStalled() {
        LocalDateTime deadline=LocalDateTime.now().minusMinutes(15);
        for(ConversationArtifactPO p:artifacts.stalled(deadline))
            if(artifacts.failStalled(p.getId(),deadline)==1) changed(p);
        try(var files=Files.list(root())) {
            for(Path p:files.filter(f -> f.getFileName().toString().matches("upload-.*\\.part")).toList())
                if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(p).toMillis()<System.currentTimeMillis()-86400000L) Files.deleteIfExists(p);
        } catch(IOException e) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Artifact temporary cleanup will retry");}
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_REQUEST,message);}
    private static BusinessException conflict(String message){return new BusinessException(ErrorCode.CONFLICT,message);}
    private static BusinessException missing(){return new BusinessException(ErrorCode.NOT_FOUND,"产物不存在或不可访问");}
}
