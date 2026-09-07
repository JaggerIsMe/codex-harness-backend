package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AttachmentProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Project authorization, durable operations and remote files behind one Interface. */
@Service
public class WorkspaceFileService {
    private static final Set<String> HIDDEN_NAMES = Set.of(".codex", ".git", ".harness", ".agent", ".agents", ".harness-workspace.json");
    private final ProjectMapper projects;
    private final AgentDeviceMapper devices;
    private final WorkspaceFileOperationMapper operations;
    private final AuthorizationService access;
    private final DeviceAuthenticationService authentication;
    private final AgentCommandGateway gateway;
    private final AttachmentProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ClientEventWebSocketHandler events;
    private final Map<Long,Long> dirtyProjects=new ConcurrentHashMap<>();

    public WorkspaceFileService(ProjectMapper projects, AgentDeviceMapper devices, WorkspaceFileOperationMapper operations,
            AuthorizationService access, DeviceAuthenticationService authentication, AgentCommandGateway gateway,
            AttachmentProperties properties, StringRedisTemplate redis, ObjectMapper json, ClientEventWebSocketHandler events) {
        this.projects=projects; this.devices=devices; this.operations=operations; this.access=access;
        this.authentication=authentication; this.gateway=gateway; this.properties=properties;
        this.redis=redis; this.json=json; this.events=events;
    }

    public WorkspaceDirectoryVO directory(Long pid,Long uid,String path,String cursor,boolean refresh) {
        ProjectPO p=owned(pid,uid,false); validatePath(path); validateCursor(cursor);
        if (!visiblePath(path)) throw invalid("内部目录不在工作区目录树中展示");
        remember(pid,path);
        var op=operations.latest(pid,path,cursor);
        WorkspaceDirectoryVO cached=readCache(pid,path,cursor);
        if (supported(p) && online(p) && (refresh || op==null || cached==null && "SUCCEEDED".equals(op.getStatus()))) op=sync(p,path,cursor);
        return new WorkspaceDirectoryVO(path,cached==null ? "0" : cached.generation(),cached==null ? 0 : cached.scannedAt(),
                cached==null ? List.of() : cached.entries(),cached==null ? null : cached.nextCursor(),cached!=null,
                online(p),supported(p),op==null ? null : new WorkspaceFileOperationVO(op),properties.getMaxFileBytes());
    }

    public WorkspaceFileOperationVO createDirectory(Long pid,Long uid,WorkspaceFileRequestDTO request) {
        ProjectPO p=owned(pid,uid,true); requireReady(p); validatePath(request.path());
        if(request.path().isEmpty()) throw invalid("不能创建工作区根目录");
        return new WorkspaceFileOperationVO(create(p,"CREATE_WORKSPACE_DIRECTORY",request.path(),"",request.requestKey(),null,0,null,null));
    }

    public WorkspaceFileOperationVO download(Long pid,Long uid,WorkspaceFileRequestDTO request) {
        ProjectPO p=owned(pid,uid,false); requireReady(p); validatePath(request.path());
        if(request.path().isEmpty()) throw invalid("请选择文件");
        return new WorkspaceFileOperationVO(create(p,"PREPARE_WORKSPACE_DOWNLOAD",request.path(),"",request.requestKey(),UUID.randomUUID().toString(),0,null,null));
    }

    public WorkspaceFileOperationVO upload(Long pid,Long uid,String parent,String requestKey,MultipartFile file) throws IOException {
        ProjectPO p=owned(pid,uid,true); requireReady(p); validatePath(parent);
        String name=file.getOriginalFilename(); validatePath(name);
        if(name==null || name.isEmpty() || name.contains("/")) throw invalid("文件名无效");
        String path=parent.isEmpty() ? name : parent+"/"+name; validatePath(path);
        String key=UUID.randomUUID().toString(); Path target=storage(key);
        try {
            Content content=store(file.getInputStream(),target,properties.getMaxFileBytes());
            var op=create(p,"UPLOAD_WORKSPACE_FILE",path,"",requestKey,key,content.size(),content.sha(),null);
            if(!key.equals(op.getStorageKey())) Files.deleteIfExists(target);
            return new WorkspaceFileOperationVO(op);
        } catch(Exception e) {Files.deleteIfExists(target);throw e;}
    }

    public WorkspaceFileOperationVO uploadAttachment(ConversationAttachmentPO attachment) {
        ProjectPO p=owned(attachment.getProjectId(),attachment.getUserId(),true); requireReady(p);
        validatePath(attachment.getFileName());
        return new WorkspaceFileOperationVO(create(p,"UPLOAD_WORKSPACE_FILE",attachment.getFileName(),"",UUID.randomUUID().toString(),
                attachment.getStorageKey(),attachment.getSizeBytes(),attachment.getSha256(),attachment.getId()));
    }

    public WorkspaceFileOperationVO operation(Long pid,Long uid,Long id) {
        owned(pid,uid,false); return new WorkspaceFileOperationVO(requireOperation(pid,id));
    }

    public void requireUploaded(ConversationAttachmentPO p) {
        if(p.getWorkspaceOperationId()==null) return; // Legacy immutable attachments.
        var op=requireOperation(p.getProjectId(),p.getWorkspaceOperationId());
        if(!"SUCCEEDED".equals(op.getStatus()) || !Objects.equals(op.getAttachmentId(),p.getId()))
            throw new BusinessException(ErrorCode.CONFLICT,"附件尚未写入工作区，请等待上传完成或重新上传");
    }

    public AttachmentFileVO downloadContent(Long pid,Long uid,Long id) throws IOException {
        owned(pid,uid,false); var op=requireOperation(pid,id);
        if(!"PREPARE_WORKSPACE_DOWNLOAD".equals(op.getKind()) || !"SUCCEEDED".equals(op.getStatus())) throw invalid("下载尚未就绪或已过期");
        return contentFile(op);
    }

    public WorkspaceFileCommandDTO agentManifest(Long id,String code,String auth) {return command(agentOperation(id,code,auth));}

    public AttachmentFileVO agentContent(Long id,String code,String auth) throws IOException {
        var op=agentOperation(id,code,auth);
        if(!"UPLOAD_WORKSPACE_FILE".equals(op.getKind())) throw invalid("操作不允许下载上传内容");
        return contentFile(op);
    }

    public void receiveContent(Long id,String code,String auth,String sha,InputStream input) throws IOException {
        var op=agentOperation(id,code,auth);
        if(!"PREPARE_WORKSPACE_DOWNLOAD".equals(op.getKind()) || sha==null || !sha.matches("[a-f0-9]{64}")) throw invalid("下载传输参数无效");
        Path target=storage(op.getStorageKey());
        Content content=store(input,target,properties.getMaxFileBytes());
        if(!sha.equals(content.sha())) {Files.deleteIfExists(target);throw invalid("下载内容校验失败");}
        try {
            agentOperation(id,code,auth); // Recheck revoked assignments and timeouts after the stream.
            if(operations.content(id,content.size(),content.sha())!=1) throw invalid("下载操作已失效");
        } catch(RuntimeException e) {Files.deleteIfExists(target);throw e;}
    }

    public synchronized void result(Long deviceId,WorkspaceFileResultDTO result) {
        Long id=parseId(result.operationId());
        var op=operations.get(id);
        if(op==null || !deviceId.equals(op.getDeviceId()) || !"RUNNING".equals(op.getStatus())) return;
        try {
            owned(op.getProjectId(),op.getUserId(),isWrite(op));
            if(!result.success()) {finish(op,"FAILED",shortError(result.error()));return;}
            if("SYNC_WORKSPACE_TREE".equals(op.getKind())) {
                validateEntries(op,result);
                var latest=operations.latest(op.getProjectId(),op.getPath(),op.getCursor());
                if(latest!=null && latest.getId().equals(id)) {
                    if(op.getCursor().isEmpty()) {
                        var keys=redis.opsForHash().keys(cacheKey(op.getProjectId()));
                        for(Object key:keys) if(key.toString().startsWith(op.getPath()+"\n") && !key.equals(field(op.getPath(),"")))
                            redis.opsForHash().delete(cacheKey(op.getProjectId()),key);
                    }
                    var cached=new WorkspaceDirectoryVO(op.getPath(),id.toString(),result.scannedAt(),visibleEntries(result.entries()),result.nextCursor(),true,true,true,null,properties.getMaxFileBytes());
                    redis.opsForHash().put(cacheKey(op.getProjectId()),field(op.getPath(),op.getCursor()),json.writeValueAsString(cached));
                    redis.expire(cacheKey(op.getProjectId()),Duration.ofDays(7));
                }
            } else if("PREPARE_WORKSPACE_DOWNLOAD".equals(op.getKind())) {
                if(op.getSha256()==null || !op.getSha256().equals(result.sha256()) || op.getSizeBytes()!=result.sizeBytes()) throw invalid("下载内容尚未接收或校验不匹配");
            } else if("UPLOAD_WORKSPACE_FILE".equals(op.getKind()) &&
                    (!Objects.equals(op.getSha256(),result.sha256()) || op.getSizeBytes()!=result.sizeBytes())) throw invalid("上传结果校验不匹配");
            finish(op,"SUCCEEDED",null);
            if(isWrite(op)) refreshProject(op.getProjectId(),op.getUserId());
        } catch(Exception e) {finish(op,"FAILED",shortError(e.getMessage()));}
    }

    public void commandError(Long deviceId,String operationId,String message) {
        var op=operations.get(parseId(operationId));
        if(op!=null && deviceId.equals(op.getDeviceId())) finish(op,"FAILED",shortError(message));
    }

    /** Called after a terminal transaction commits, even with no browser connected. */
    public void refreshProject(Long pid,Long uid) {if(pid!=null && uid!=null) dirtyProjects.put(pid,uid);}

    public void reconnected(Long deviceId) {
        for(var op:operations.runningForDevice(deviceId)) finish(op,"FAILED","Agent 已重连，请刷新目录核实上次操作结果");
        for(var p:projects.selectForDevice(deviceId)) refreshProject(p.getId(),p.getUserId());
    }

    public void detachedAttachment(Long id) {
        if(id==null) return;
        var op=operations.get(id);
        if(op!=null) {finish(op,"FAILED","附件关联已移除");operations.detach(id);}
    }

    @Scheduled(fixedDelay=1000)
    public synchronized void dispatch() {
        for(var entry:new ArrayList<>(dirtyProjects.entrySet())) {
            if(operations.running(entry.getKey())>0) continue;
            try {
                var p=owned(entry.getKey(),entry.getValue(),false);
                if(!online(p) || !supported(p)) continue;
                dirtyProjects.remove(entry.getKey(),entry.getValue());
                Set<String> paths=redis.opsForSet().members(pathsKey(p.getId()));
                sync(p,"","");
                if(paths!=null) for(String path:paths) {
                    if(!visiblePath(path)) redis.opsForSet().remove(pathsKey(p.getId()),path);
                    else if(!path.isEmpty()) sync(p,path,"");
                }
            } catch(Exception e) {dirtyProjects.remove(entry.getKey(),entry.getValue());log("目录刷新失败",e);}
        }
        for(var op:operations.queued()) {
            if(operations.running(op.getProjectId())>0) continue;
            try {
                var p=owned(op.getProjectId(),op.getUserId(),isWrite(op));
                if(!online(p)) continue;
                requireReady(p);
                if(operations.start(op.getId())==1) gateway.send(p.getDeviceCode(),new AgentCommand(op.getKind(),op.getId().toString(),command(op),op.getRequestKey()));
            } catch(Exception e) {finish(op,"FAILED",shortError(e.getMessage()));}
        }
        for(var op:operations.timedOut(LocalDateTime.now().minusMinutes(5))) {
            finish(op,"FAILED","操作超时，请刷新目录核实结果后重试");
            if(isWrite(op)) refreshProject(op.getProjectId(),op.getUserId());
        }
    }

    @Scheduled(fixedDelay=3600000)
    public void cleanup() {
        for(var op:operations.expired(LocalDateTime.now().minusHours(24))) {
            if(op.getAttachmentId()!=null) continue;
            try {
                if(op.getStorageKey()!=null) Files.deleteIfExists(storage(op.getStorageKey()));
                operations.expire(op.getId());
            } catch(Exception e) {log("工作区传输清理失败",e);}
        }
    }

    private synchronized WorkspaceFileOperationPO sync(ProjectPO p,String path,String cursor) {
        var previous=operations.latest(p.getId(),path,cursor);
        if(previous!=null && Set.of("QUEUED","RUNNING").contains(previous.getStatus())) {
            if("RUNNING".equals(previous.getStatus())) refreshProject(p.getId(),p.getUserId());
            return previous;
        }
        return create(p,"SYNC_WORKSPACE_TREE",path,cursor,UUID.randomUUID().toString(),null,0,null,null);
    }

    private synchronized WorkspaceFileOperationPO create(ProjectPO p,String kind,String path,String cursor,String requestKey,
            String storageKey,long size,String sha,Long attachmentId) {
        try {UUID.fromString(requestKey);} catch(Exception e) {throw invalid("请求标识无效");}
        var existing=operations.request(p.getId(),requestKey);
        if(existing!=null) {
            if(!existing.getKind().equals(kind) || !existing.getPath().equals(path) || !Objects.equals(existing.getSha256(),sha) || existing.getSizeBytes()!=size)
                throw new BusinessException(ErrorCode.CONFLICT,"请求标识已用于其他文件操作");
            return existing;
        }
        if(operations.activeCount(p.getId())>=100) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,"工作区待处理操作过多");
        var op=new WorkspaceFileOperationPO();
        op.setRequestKey(requestKey);op.setUserId(p.getUserId());op.setProjectId(p.getId());op.setDeviceId(p.getDeviceId());
        op.setWorkspaceName(p.getWorkspaceName());op.setKind(kind);op.setPath(path);op.setCursor(cursor);
        op.setStorageKey(storageKey);op.setSizeBytes(size);op.setSha256(sha);op.setAttachmentId(attachmentId);op.setStatus("QUEUED");
        operations.insert(op);return op;
    }

    private WorkspaceFileOperationPO agentOperation(Long id,String code,String auth) {
        var device=authentication.authenticate(code,auth);var op=operations.get(id);
        if(op==null || !device.getId().equals(op.getDeviceId()) || !"RUNNING".equals(op.getStatus())) throw missing();
        var p=owned(op.getProjectId(),op.getUserId(),isWrite(op));
        if(!p.getDeviceId().equals(op.getDeviceId()) || !p.getWorkspaceName().equals(op.getWorkspaceName())) throw missing();
        return op;
    }
    private WorkspaceFileOperationPO requireOperation(Long pid,Long id) {
        var op=operations.get(id);if(op==null || !pid.equals(op.getProjectId())) throw missing();return op;
    }
    private ProjectPO owned(Long pid,Long uid,boolean write) {
        access.requirePermission(uid,"conversation:read");if(write) access.requirePermission(uid,"turn:start");
        var p=projects.selectOwned(pid,uid);
        if(p==null || !"ACTIVE".equals(p.getStatus())) throw missing();
        access.requireDevice(uid,p.getDeviceId());return p;
    }
    private boolean supported(ProjectPO p) {var d=devices.selectById(p.getDeviceId());return d!=null && Boolean.TRUE.equals(d.getWorkspaceFiles());}
    private boolean online(ProjectPO p) {return gateway.isOnline(p.getDeviceCode());}
    private void requireReady(ProjectPO p) {
        if(!supported(p)) throw new BusinessException(ErrorCode.CONFLICT,"请升级 Agent 以支持工作区文件管理");
        if(!online(p)) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        if(!"ENABLED".equals(p.getWorkspaceStatus())) throw new BusinessException(ErrorCode.CONFLICT,"工作区尚未就绪");
    }
    private static boolean isWrite(WorkspaceFileOperationPO op) {return Set.of("UPLOAD_WORKSPACE_FILE","CREATE_WORKSPACE_DIRECTORY").contains(op.getKind());}
    private WorkspaceFileCommandDTO command(WorkspaceFileOperationPO op) {
        // Download size/hash change as bytes arrive; the authorization manifest remains stable.
        boolean upload="UPLOAD_WORKSPACE_FILE".equals(op.getKind());
        return new WorkspaceFileCommandDTO(op.getId().toString(),op.getProjectId().toString(),op.getWorkspaceName(),op.getPath(),op.getCursor(),upload ? op.getSizeBytes() : 0,upload ? op.getSha256() : null);
    }
    private void finish(WorkspaceFileOperationPO op,String status,String error) {
        if(operations.finish(op.getId(),status,error)==1) events.sendToUser(op.getUserId(),
                new WorkspaceFilesChangedVO("WORKSPACE_FILES_CHANGED",op.getDeviceId(),new WorkspaceFilesChangedVO.Payload(op.getProjectId().toString(),op.getPath(),op.getId().toString())));
    }
    private void remember(Long pid,String path) {
        Long count=redis.opsForSet().size(pathsKey(pid));
        if(count==null || count<64) redis.opsForSet().add(pathsKey(pid),path);
        redis.expire(pathsKey(pid),Duration.ofDays(7));
    }
    private WorkspaceDirectoryVO readCache(Long pid,String path,String cursor) {
        Object value=redis.opsForHash().get(cacheKey(pid),field(path,cursor));
        if(value==null) return null;
        try {
            var cached=json.readValue(value.toString(),WorkspaceDirectoryVO.class);
            return new WorkspaceDirectoryVO(cached.path(),cached.generation(),cached.scannedAt(),visibleEntries(cached.entries()),
                    cached.nextCursor(),cached.loaded(),cached.online(),cached.supported(),cached.operation(),cached.maxFileBytes());
        } catch(IOException e) {return null;}
    }
    private static List<WorkspaceFileEntryVO> visibleEntries(List<WorkspaceFileEntryVO> entries) {
        return entries.stream().filter(entry -> visiblePath(entry.path())).toList();
    }
    private static boolean visiblePath(String path) {
        for (String part : path.split("/")) {
            String name = part.toLowerCase(Locale.ROOT);
            if (HIDDEN_NAMES.contains(name) || name.startsWith(".harness-upload-")) return false;
        }
        return true;
    }
    private void validateEntries(WorkspaceFileOperationPO op,WorkspaceFileResultDTO r) {
        if(r.entries()==null || r.entries().size()>200) throw invalid("目录结果超限");
        Set<String> paths=new HashSet<>();String previous=op.getCursor();
        for(var e:r.entries()) {
            validatePath(e.path());
            if(e.name()==null || e.name().contains("/") || !e.path().equals(op.getPath().isEmpty() ? e.name() : op.getPath()+"/"+e.name()) ||
                    !paths.add(e.path()) || e.name().compareTo(previous)<=0 || !Set.of("FILE","DIRECTORY","UNAVAILABLE").contains(e.type()) || e.sizeBytes()<0) throw invalid("目录节点无效");
            previous=e.name();
        }
        if(r.nextCursor()!=null && (r.entries().isEmpty() || !r.nextCursor().equals(previous))) throw invalid("目录分页游标无效");
    }
    public static void validatePath(String path) {
        if(path==null || path.length()>2048 || path.startsWith("/") || path.contains("\\")) throw invalid("工作区相对路径无效");
        if(path.isEmpty()) return;
        for(String part:path.split("/",-1)) if(part.isBlank() || part.equals(".") || part.equals("..") || part.length()>255 || part.endsWith(".") || part.endsWith(" ") ||
                part.chars().anyMatch(c -> c<32 || c==127 || "<>:\"|?*".indexOf(c)>=0) || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) throw invalid("文件或目录名称无效");
    }
    private static void validateCursor(String cursor) {validatePath(cursor);if(cursor.contains("/")) throw invalid("目录游标无效");}
    private Path storage(String key) throws IOException {
        if(key==null || !key.matches("[a-f0-9-]{36}")) throw missing();
        Path root=Path.of(properties.getStorageDir()).toAbsolutePath().normalize();Files.createDirectories(root);return root.toRealPath().resolve(key);
    }
    private AttachmentFileVO contentFile(WorkspaceFileOperationPO op) throws IOException {
        Path path=storage(op.getStorageKey());
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=op.getSizeBytes()) throw missing();
        return new AttachmentFileVO(new FileSystemResource(path),op.getPath().substring(op.getPath().lastIndexOf('/')+1),op.getSizeBytes());
    }
    private record Content(long size,String sha) {}
    private Content store(InputStream input,Path target,long limit) throws IOException {
        Path temporary=Files.createTempFile(target.getParent(),"workspace-transfer-",".part");
        try {
            MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}
            long total=0;byte[] buffer=new byte[8192];int n;
            try(input;var output=Files.newOutputStream(temporary)) {
                while((n=input.read(buffer))!=-1) {total+=n;if(total>limit) throw invalid("文件超过大小限制");digest.update(buffer,0,n);output.write(buffer,0,n);}
            }
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
            return new Content(total,HexFormat.of().formatHex(digest.digest()));
        } finally {Files.deleteIfExists(temporary);}
    }
    private static String cacheKey(Long pid) {return "harness:workspace-files:"+pid+":pages";}
    private static String pathsKey(Long pid) {return "harness:workspace-files:"+pid+":paths";}
    private static String field(String path,String cursor) {return path+"\n"+cursor;}
    private static Long parseId(String value) {try{return Long.valueOf(value);}catch(Exception e){throw invalid("操作标识无效");}}
    private static String shortError(String text) {return text==null ? "文件操作失败" : text.substring(0,Math.min(1000,text.length()));}
    private static BusinessException invalid(String message) {return new BusinessException(ErrorCode.INVALID_REQUEST,message);}
    private static BusinessException missing() {return new BusinessException(ErrorCode.NOT_FOUND,"工作区文件操作不存在或不可访问");}
    private void log(String message,Exception e) {org.slf4j.LoggerFactory.getLogger(getClass()).warn(message,e);}
}
