package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.WorkspaceFileProperties;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Project authorization, durable operations and remote files behind one Interface. */
@Service
public class WorkspaceFileService {
    private static final Set<String> MUTATIONS=Set.of("RELOCATE_WORKSPACE_ENTRY","DELETE_WORKSPACE_ENTRY");
    private static final Set<String> ACTIONS=Set.of("RELOCATE_WORKSPACE_ENTRY","DELETE_WORKSPACE_ENTRY","PREPARE_WORKSPACE_DELETE","PREPARE_WORKSPACE_ARCHIVE");
    private static final int MAX_REQUEST_BYTES=512*1024,MAX_ITEMS_BYTES=8*1024*1024;
    private ExpertMapper experts;
    private TransactionTemplate transactions;
    private TransactionTemplate resultTransactions;
    private WorkspaceAttachmentLocationService attachmentLocations;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureActions(ExpertMapper experts,TransactionTemplate transactions,WorkspaceAttachmentLocationService attachmentLocations) {
        this.experts=experts;this.transactions=transactions;this.attachmentLocations=attachmentLocations;
        if(transactions.getTransactionManager()!=null) {
            this.resultTransactions=new TransactionTemplate(transactions.getTransactionManager());
            this.resultTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        } else this.resultTransactions=transactions;
    }
    private static final Set<String> HIDDEN_NAMES = Set.of(".codex", ".git", ".harness", ".agent", ".agents", ".harness-workspace.json");
    private final ProjectMapper projects;
    private final AgentDeviceMapper devices;
    private final WorkspaceFileOperationMapper operations;
    private final AuthorizationService access;
    private final DeviceAuthenticationService authentication;
    private final AgentCommandGateway gateway;
    private final WorkspaceFileProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ClientEventWebSocketHandler events;
    private final Map<Long,Long> dirtyProjects=new ConcurrentHashMap<>();

    public WorkspaceFileService(ProjectMapper projects, AgentDeviceMapper devices, WorkspaceFileOperationMapper operations,
            AuthorizationService access, DeviceAuthenticationService authentication, AgentCommandGateway gateway,
            WorkspaceFileProperties properties, StringRedisTemplate redis, ObjectMapper json, ClientEventWebSocketHandler events) {
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
                online(p),supported(p),op==null ? null : new WorkspaceFileOperationVO(op),effectiveLimits(p).maxFileBytes(),capabilities(p),limits(p));
    }

    /** The caller must already hold the Project row lock when admitting a Turn or attachment transfer. */
    public void assertNoMutation(Long pid) {
        if(operations.mutationCount(pid)>0) throw new BusinessException(ErrorCode.CONFLICT,"工作区文件正在修改或结果待核实，请完成后再启动任务");
    }

    public WorkspaceFileOperationVO rename(Long pid,Long uid,WorkspaceFileActionRequestDTO r) {
        protectedPath(r.path(),false);protectedPath(r.name(),false);revision(r.expectedRevision());
        if(r.name().contains("/")) throw invalid("重命名只能输入名称");
        String target=join(parent(r.path()),r.name());
        if(target.equals(r.path())) throw invalid("文件名称未改变");
        return action(pid,uid,"RENAME","RELOCATE_WORKSPACE_ENTRY",r,r.path(),target,null);
    }

    public WorkspaceFileOperationVO move(Long pid,Long uid,WorkspaceFileActionRequestDTO r) {
        protectedPath(r.path(),false);protectedPath(r.targetDirectory(),true);revision(r.expectedRevision());
        String target=join(r.targetDirectory(),r.path().substring(r.path().lastIndexOf('/')+1));
        if(target.equals(r.path())) throw invalid("目标目录与当前目录相同");
        return action(pid,uid,"MOVE","RELOCATE_WORKSPACE_ENTRY",r,r.path(),target,null);
    }

    public WorkspaceFileOperationVO deletePlan(Long pid,Long uid,WorkspaceFileActionRequestDTO r) {
        protectedPath(r.path(),false);revision(r.expectedRevision());
        return action(pid,uid,"DELETE_PLAN","PREPARE_WORKSPACE_DELETE",r,r.path(),null,null);
    }

    public WorkspaceFileOperationVO delete(Long pid,Long uid,WorkspaceFileActionRequestDTO r) {
        requireUuid(r.requestKey());requireUuid(r.planId());
        if(r.planDigest()==null || !r.planDigest().matches("[a-f0-9]{64}")) throw invalid("删除计划摘要无效");
        return projectTransaction(pid,() -> {
            var p=owned(pid,uid,true);
            String digest=requestDigest("DELETE",r);
            var duplicate=duplicate(p,r.requestKey(),digest);if(duplicate!=null) return new WorkspaceFileOperationVO(duplicate);
            requireActionReady(p,true);assertMutationAdmission(pid);
            var planned=operations.deletePlan(pid,r.planId());
            if(planned==null || !Objects.equals(planned.getDeviceId(),p.getDeviceId()) || !Objects.equals(planned.getWorkspaceName(),p.getWorkspaceName())) throw invalid("删除计划不存在，请重新检查");
            var result=readResult(planned);var plan=result==null ? null : result.plan();
            if(plan==null || !r.planDigest().equals(plan.planDigest()) || plan.expiresAt()<=System.currentTimeMillis()) throw new BusinessException(ErrorCode.CONFLICT,"删除计划已过期或不匹配，请重新检查");
            if(operations.planConsumer(planned.getId())!=null) throw new BusinessException(ErrorCode.CONFLICT,"删除计划已经使用，请重新检查");
            return new WorkspaceFileOperationVO(createAction(p,"DELETE_WORKSPACE_ENTRY",plan.path(),null,r,plan.entryRevision(),digest,planned.getId()));
        });
    }

    public WorkspaceFileOperationVO archive(Long pid,Long uid,WorkspaceFileActionRequestDTO r) {
        if(r.items()==null || r.items().isEmpty() || r.items().size()>properties.getMaxArchiveFiles()) throw invalid("所选文件数量超过限制或未选择文件");
        var unique=new TreeMap<String,WorkspaceFileActionRequestDTO.Item>();var portable=new HashSet<String>();
        for(var item:r.items()) {
            if(item==null) throw invalid("所选文件无效");protectedPath(item.path(),false);revision(item.expectedRevision());
            var prior=unique.putIfAbsent(item.path(),item);
            if(prior!=null && !prior.expectedRevision().equals(item.expectedRevision())) throw invalid("同一文件有不同版本，请刷新目录");
            if(prior==null && !portable.add(item.path().toLowerCase(Locale.ROOT))) throw invalid("所选文件存在大小写路径冲突");
        }
        var canonical=new WorkspaceFileActionRequestDTO(r.requestKey(),null,null,null,null,null,null,List.copyOf(unique.values()));
        return action(pid,uid,"ARCHIVE","PREPARE_WORKSPACE_ARCHIVE",canonical,"workspace-files.zip",null,null);
    }

    public WorkspaceFilePageVO<WorkspaceFileOperationVO> recent(Long pid,Long uid,String cursor,int limit) {
        owned(pid,uid,false);pageLimit(limit);Long before=cursor==null || cursor.isBlank() ? null : positiveId(cursor);
        var rows=operations.recent(pid,before,limit+1);boolean more=rows.size()>limit;
        var page=rows.stream().limit(limit).map(WorkspaceFileOperationVO::new).toList();
        return new WorkspaceFilePageVO<>(page,more ? page.getLast().id() : null);
    }

    public WorkspaceFilePageVO<WorkspaceFileItemsDTO.Item> items(Long pid,Long uid,Long id,String cursor,int limit) {
        owned(pid,uid,false);requireOperation(pid,id);pageLimit(limit);
        long after=cursor==null || cursor.isBlank() ? -1 : nonnegativeId(cursor);
        var rows=operations.items(id,after,limit+1);boolean more=rows.size()>limit;
        return new WorkspaceFilePageVO<>(rows.stream().limit(limit).map(WorkspaceFileItemPO::toItem).toList(),more ? Long.toString(rows.get(limit-1).getItemIndex()) : null);
    }

    public WorkspaceFileOperationVO reconcile(Long pid,Long uid,Long id,String requestKey) {
        requireUuid(requestKey);var p=owned(pid,uid,true);var op=requireOperation(pid,id);
        if(!isMutation(op)) throw invalid("此操作不需要核实");
        if("UNKNOWN".equals(op.getStatus()) && !recoverUnsentCommand(op)) {
            op=requireOperation(pid,id);
            if("UNKNOWN".equals(op.getStatus())) {requireActionReady(p,true);sendReconcile(p,op,requestKey);}
        }
        return new WorkspaceFileOperationVO(requireOperation(pid,id));
    }

    private boolean recoverUnsentCommand(WorkspaceFileOperationPO op) {
        if(!isLegacyEncodingFailure(op)) return false;
        var result=new WorkspaceFileResultDTO(op.getId().toString(),false,"命令编码失败，未发送到 Agent，文件未修改；请刷新目录后重试",
                List.of(),null,0,0,null,1,"FAILED","NO_CHANGE","COMMAND_NOT_SENT",
                op.getPath(),op.getTargetPath(),null,null,null,null,null,null);
        // Recheck persisted evidence under the same Project lock that commits result and attachment locations.
        return completeAction(op,result,this::isLegacyEncodingFailure);
    }

    private boolean isLegacyEncodingFailure(WorkspaceFileOperationPO op) {
        if(op==null || !isMutation(op) || !"UNKNOWN".equals(op.getStatus()) || !"RESULT_UNKNOWN".equals(op.getCode())) return false;
        String error="No enum constant com.myharness.codex.entity.enums.AgentCommandType."+op.getKind();
        if(!error.equals(op.getError())) return false;
        var result=readResult(op);
        // This exact Server enum error was thrown by the codec before socket.sendMessage.
        return result!=null && "UNKNOWN".equals(result.status()) && "UNKNOWN".equals(result.outcome()) &&
                "RESULT_UNKNOWN".equals(result.code()) && error.equals(result.error()) &&
                Objects.equals(op.getPath(),result.sourcePath()) && Objects.equals(op.getTargetPath(),result.targetPath());
    }

    private WorkspaceFileOperationVO action(Long pid,Long uid,String action,String kind,WorkspaceFileActionRequestDTO r,String path,String target,Long plan) {
        requireUuid(r.requestKey());String digest=requestDigest(action,r);
        return projectTransaction(pid,() -> {
            var p=owned(pid,uid,!"PREPARE_WORKSPACE_ARCHIVE".equals(kind));
            var duplicate=duplicate(p,r.requestKey(),digest);if(duplicate!=null) return new WorkspaceFileOperationVO(duplicate);
            requireActionReady(p,!"PREPARE_WORKSPACE_ARCHIVE".equals(kind));
            if(MUTATIONS.contains(kind)) assertMutationAdmission(pid);else assertNoMutation(pid);
            return new WorkspaceFileOperationVO(createAction(p,kind,path,target,r,r.expectedRevision(),digest,plan));
        });
    }

    private WorkspaceFileOperationPO createAction(ProjectPO p,String kind,String path,String target,WorkspaceFileActionRequestDTO r,String revision,String digest,Long plan) {
        var op=create(p,kind,path,"",r.requestKey(),"PREPARE_WORKSPACE_ARCHIVE".equals(kind) ? UUID.randomUUID().toString() : null,0,null,null);
        op.setTargetPath(target);op.setRequestDigest(digest);op.setDeletePlanOperationId(plan);
        op.setContentState(op.getStorageKey()==null ? "NONE" : "PENDING");
        if("PREPARE_WORKSPACE_DELETE".equals(kind)) op.setAttachmentCount(attachmentLocations.countAvailable(p.getId(),path));
        var command=new WorkspaceFileCommandDTO(op.getId().toString(),p.getId().toString(),p.getWorkspaceName(),path,"",0,null,target,revision,
                r.planId(),r.planDigest(),r.items(),digest,effectiveLimits(p),null);
        op.setPayloadJson(encode(command));
        if(r.items()!=null && r.items().size()>command.limits().maxFiles() || op.getPayloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>command.limits().maxRequestBytes()-4096)
            throw invalid("文件选择超过当前 Agent 限制");
        operations.actionPayload(op);
        if(isMutation(op)) attachmentLocations.claim(p.getId(),path,op.getId());
        return op;
    }

    private WorkspaceFileOperationPO duplicate(ProjectPO p,String key,String digest) {
        var op=operations.request(p.getId(),key);
        if(op!=null && !digest.equals(op.getRequestDigest())) throw new BusinessException(ErrorCode.CONFLICT,"请求标识已用于其他文件操作");
        return op;
    }

    private String requestDigest(String action,WorkspaceFileActionRequestDTO r) {
        var canonical=new WorkspaceFileActionRequestDTO(null,r.path(),r.name(),r.targetDirectory(),r.expectedRevision(),r.planId(),r.planDigest(),r.items());
        byte[] bytes=encode(List.of(action,canonical)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>MAX_REQUEST_BYTES-32*1024) throw invalid("文件选择请求过大");
        return digest(bytes);
    }

    private void assertMutationAdmission(Long pid) {
        assertNoMutation(pid);
        if(experts.activeTurns(pid)>0) throw new BusinessException(ErrorCode.CONFLICT,"请结束项目中的任务后再修改文件");
    }

    private <T> T projectTransaction(Long pid,Supplier<T> work) {
        if(transactions==null || experts==null || attachmentLocations==null) throw new IllegalStateException("Workspace action coordination is not configured");
        return transactions.execute(tx -> {if(experts.lockProject(pid)==null) throw missing();return work.get();});
    }

    public WorkspaceFileOperationVO createDirectory(Long pid,Long uid,WorkspaceFileRequestDTO request) {
        ProjectPO p=owned(pid,uid,true); requireReady(p); validatePath(request.path());
        protectedPath(request.path(),false);assertNoMutation(pid);
        if(request.path().isEmpty()) throw invalid("不能创建工作区根目录");
        return new WorkspaceFileOperationVO(create(p,"CREATE_WORKSPACE_DIRECTORY",request.path(),"",request.requestKey(),null,0,null,null));
    }

    public WorkspaceFileOperationVO download(Long pid,Long uid,WorkspaceFileRequestDTO request) {
        ProjectPO p=owned(pid,uid,false); requireReady(p); validatePath(request.path());
        protectedPath(request.path(),false);assertNoMutation(pid);
        if(request.path().isEmpty()) throw invalid("请选择文件");
        return new WorkspaceFileOperationVO(create(p,"PREPARE_WORKSPACE_DOWNLOAD",request.path(),"",request.requestKey(),UUID.randomUUID().toString(),0,null,null));
    }

    public WorkspaceFileOperationVO upload(Long pid,Long uid,String parent,String requestKey,MultipartFile file) throws IOException {
        ProjectPO p=owned(pid,uid,true); requireReady(p); validatePath(parent);
        String name=file.getOriginalFilename(); validatePath(name);
        if(name==null || name.isEmpty() || name.contains("/")) throw invalid("文件名无效");
        String path=parent.isEmpty() ? name : parent+"/"+name; validatePath(path);
        protectedPath(path,false);assertNoMutation(pid);
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
        protectedPath(attachment.getFileName(),false);assertNoMutation(p.getId());
        return new WorkspaceFileOperationVO(create(p,"UPLOAD_WORKSPACE_FILE",attachment.getFileName(),"",UUID.randomUUID().toString(),
                attachment.getStorageKey(),attachment.getSizeBytes(),attachment.getSha256(),attachment.getId()));
    }

    public WorkspaceFileOperationVO operation(Long pid,Long uid,Long id) {
        owned(pid,uid,false); return new WorkspaceFileOperationVO(requireOperation(pid,id));
    }

    public void requireUploaded(ConversationAttachmentPO p) {
        if(p.getWorkspaceOperationId()==null || p.getWorkspacePath()==null || p.getWorkspacePath().isBlank())
            throw new BusinessException(ErrorCode.CONFLICT,"附件缺少工作区文件关联，请重新上传");
        var op=requireOperation(p.getProjectId(),p.getWorkspaceOperationId());
        if(!"SUCCEEDED".equals(op.getStatus()) || !Objects.equals(op.getAttachmentId(),p.getId()))
            throw new BusinessException(ErrorCode.CONFLICT,"附件尚未写入工作区，请等待上传完成或重新上传");
    }

    public WorkspaceFileContentVO downloadContent(Long pid,Long uid,Long id) throws IOException {
        owned(pid,uid,false); var op=requireOperation(pid,id);
        if(!Set.of("PREPARE_WORKSPACE_DOWNLOAD","PREPARE_WORKSPACE_ARCHIVE").contains(op.getKind()) || !"SUCCEEDED".equals(op.getStatus()) || "EXPIRED".equals(op.getContentState())) throw invalid("下载尚未就绪或已过期");
        return contentFile(op);
    }

    public WorkspaceFileSnapshotDTO previewSnapshot(Long pid,Long uid,Long id) throws IOException {
        owned(pid,uid,false);
        var op=requireOperation(pid,id);
        if(!"PREPARE_WORKSPACE_DOWNLOAD".equals(op.getKind()) || !"SUCCEEDED".equals(op.getStatus())) throw invalid("预览尚未就绪或已过期");
        validatePath(op.getPath());
        if(!visiblePath(op.getPath())) throw invalid("内部文件不支持预览");
        return new WorkspaceFileSnapshotDTO(id.toString(),op.getPath(),op.getSha256(),op.getUpdatedAt(),contentFile(op));
    }

    public WorkspaceFileCommandDTO agentManifest(Long id,String code,String auth) {return command(agentOperation(id,code,auth));}

    public WorkspaceFileContentVO agentContent(Long id,String code,String auth) throws IOException {
        var op=agentOperation(id,code,auth);
        if(!"UPLOAD_WORKSPACE_FILE".equals(op.getKind())) throw invalid("操作不允许下载上传内容");
        return contentFile(op);
    }

    public void receiveContent(Long id,String code,String auth,String sha,InputStream input) throws IOException {
        var op=agentOperation(id,code,auth);
        if(!Set.of("PREPARE_WORKSPACE_DOWNLOAD","PREPARE_WORKSPACE_ARCHIVE").contains(op.getKind()) || sha==null || !sha.matches("[a-f0-9]{64}")) throw invalid("下载传输参数无效");
        Path target=storage(op.getStorageKey());
        long limit="PREPARE_WORKSPACE_ARCHIVE".equals(op.getKind()) ? Math.min(properties.getMaxArchiveOutputBytes(),command(op).limits().maxOutputBytes()) : properties.getMaxFileBytes();
        Content content=store(input,target,limit);
        if(!sha.equals(content.sha())) {Files.deleteIfExists(target);throw invalid("下载内容校验失败");}
        try {
            agentOperation(id,code,auth); // Recheck revoked assignments and timeouts after the stream.
            if(operations.content(id,content.size(),content.sha())!=1) throw invalid("下载操作已失效");
        } catch(RuntimeException e) {Files.deleteIfExists(target);throw e;}
    }

    public void receiveItems(Long id,String code,String auth,String sha,InputStream input) throws IOException {
        var initial=agentOperation(id,code,auth);
        if(!ACTIONS.contains(initial.getKind()) || sha==null || !sha.matches("[a-f0-9]{64}")) throw invalid("结果明细传输参数无效");
        byte[] bytes;try(input) {bytes=input.readNBytes(MAX_ITEMS_BYTES+1);}
        if(bytes.length>MAX_ITEMS_BYTES || !sha.equals(digest(bytes))) throw invalid("结果明细超限或校验失败");
        var body=json.readValue(bytes,WorkspaceFileItemsDTO.class);
        validateItems(initial,body.items());
        projectTransaction(initial.getProjectId(),() -> {
            var op=agentOperation(id,code,auth);
            if(op.getItemsDigest()!=null) {
                if(!sha.equals(op.getItemsDigest())) throw new BusinessException(ErrorCode.CONFLICT,"结果明细与已保存内容不同");
                return null;
            }
            operations.clearItems(id);
            for(int i=0;i<body.items().size();i++) operations.insertItem(id,i,body.items().get(i));
            if(operations.itemsDigest(id,sha)!=1) throw invalid("结果明细操作已失效");
            return null;
        });
    }

    private void completeAction(WorkspaceFileOperationPO received,WorkspaceFileResultDTO result) {
        completeAction(received,result,op -> true);
    }

    private boolean completeAction(WorkspaceFileOperationPO received,WorkspaceFileResultDTO result,Predicate<WorkspaceFileOperationPO> condition) {
        return resultProjectTransaction(received.getProjectId(),() -> {
            var op=operations.get(received.getId());
            if(op==null || !Set.of("QUEUED","RUNNING","UNKNOWN").contains(op.getStatus()) || !condition.test(op)) return false;
            String status=result.status();
            if(!Set.of("SUCCEEDED","FAILED","PARTIAL_FAILED","UNKNOWN").contains(status==null ? "" : status)) throw invalid("文件操作终态无效");
            if(!Objects.equals(op.getPath(),result.sourcePath()) || !Objects.equals(op.getTargetPath(),result.targetPath())) throw invalid("文件操作结果路径不匹配");
            if(result.version()!=1 || result.success()!= "SUCCEEDED".equals(status)) throw invalid("文件操作结果版本或状态无效");
            if(!Set.of("NO_CHANGE","COMPLETE","PARTIAL","UNKNOWN").contains(Objects.toString(result.outcome(),""))) throw invalid("文件操作结果影响范围无效");
            if("SUCCEEDED".equals(status) && !"COMPLETE".equals(result.outcome()) || "PARTIAL_FAILED".equals(status) && !"PARTIAL".equals(result.outcome()) || "UNKNOWN".equals(status) && !"UNKNOWN".equals(result.outcome()) || "FAILED".equals(status) && !"NO_CHANGE".equals(result.outcome())) throw invalid("文件操作状态与影响范围不一致");
            if("UNKNOWN".equals(op.getStatus()) && "QUEUED".equals(received.getStatus())) return false;
            if(result.code()!=null && (result.code().length()>64 || !result.code().matches("[A-Z0-9_]+"))) throw invalid("结果错误码无效");
            if(result.entryType()!=null && !Set.of("FILE","DIRECTORY").contains(result.entryType())) throw invalid("结果节点类型无效");
            if("SUCCEEDED".equals(status) && "RELOCATE_WORKSPACE_ENTRY".equals(op.getKind())) {
                if(result.entryRevision()!=null) revision(result.entryRevision());
                if(result.entryType()==null) throw invalid("定位变更缺少节点类型");
            }
            if("SUCCEEDED".equals(status) && "PREPARE_WORKSPACE_DELETE".equals(op.getKind())) validatePlan(op,result.plan());
            if("SUCCEEDED".equals(status) && "PREPARE_WORKSPACE_ARCHIVE".equals(op.getKind()) &&
                    (op.getSha256()==null || !op.getSha256().equals(result.sha256()) || op.getSizeBytes()!=result.sizeBytes())) throw invalid("ZIP 内容尚未接收或校验不匹配");
            List<WorkspaceFileItemsDTO.Item> items=List.of();
            if(result.resultDigest()!=null) {
                if(!result.resultDigest().equals(op.getItemsDigest())) throw invalid("结果明细尚未接收或摘要不匹配");
                items=operations.items(op.getId(),-1,10001).stream().map(WorkspaceFileItemPO::toItem).toList();
                validateItems(op,items);
            } else if(result.items()!=null && !result.items().isEmpty()) {
                validateItems(op,result.items());items=result.items();
                operations.clearItems(op.getId());
                for(int i=0;i<items.size();i++) operations.insertItem(op.getId(),i,items.get(i));
            }
            if("PARTIAL_FAILED".equals(status) && (!"DELETE_WORKSPACE_ENTRY".equals(op.getKind()) || items.isEmpty())) throw invalid("部分删除结果缺少明细");
            if("DELETE_WORKSPACE_ENTRY".equals(op.getKind()) && Set.of("SUCCEEDED","PARTIAL_FAILED").contains(status)) validateDeleteSummary(op,result,items);
            if(!isMutation(op) && Set.of("PARTIAL_FAILED","UNKNOWN").contains(status)) throw invalid("读取操作不支持此终态");
            // Keep the WebSocket/operation response bounded; detailed paths are paginated separately.
            var compact=new WorkspaceFileResultDTO(result.operationId(),result.success(),shortNullable(result.error()),List.of(),null,result.scannedAt(),result.sizeBytes(),result.sha256(),1,status,
                    result.outcome(),result.code(),result.sourcePath(),result.targetPath(),result.entryType(),result.entryRevision(),result.plan(),result.summary(),List.of(),result.resultDigest());
            op.setResultJson(encode(compact));op.setStatus(status);op.setError(shortNullable(result.error()));op.setCode(result.code());
            if(result.plan()!=null) op.setPlanId(result.plan().planId());
            op.setContentState(op.getStorageKey()==null ? "NONE" : "SUCCEEDED".equals(status) ? "AVAILABLE" : "PENDING");
            if(operations.actionResult(op)!=1) throw new BusinessException(ErrorCode.CONFLICT,"文件操作状态已改变");
            if(isMutation(op)) attachmentLocations.apply(op.getProjectId(),op.getPath(),op.getTargetPath(),op.getId(),status,
                    items.stream().filter(i -> "DELETED".equals(i.status())).map(WorkspaceFileItemsDTO.Item::path).toList(),
                    items.stream().filter(i -> "UNKNOWN".equals(i.status())).map(WorkspaceFileItemsDTO.Item::path).toList());
            afterCommit(() -> {
                if(isMutation(op)) {invalidate(op.getProjectId());refreshProject(op.getProjectId(),op.getUserId());}
                emit(op,status,compact);
            });
            return true;
        });
    }

    private void validatePlan(WorkspaceFileOperationPO op,WorkspaceFileResultDTO.Plan plan) {
        if(plan==null) throw invalid("删除检查未返回计划");
        requireUuid(plan.planId());revision(plan.entryRevision());
        if(plan.planDigest()==null || !plan.planDigest().matches("[a-f0-9]{64}") || !op.getPath().equals(plan.path()) ||
                !Set.of("FILE","DIRECTORY").contains(Objects.toString(plan.entryType(),"")) || plan.fileCount()<0 || plan.directoryCount()<0 ||
                plan.fileCount()+plan.directoryCount()<1 || plan.fileCount()+plan.directoryCount()>10000 || plan.totalBytes()<0 ||
                plan.expiresAt()<=0) throw invalid("删除检查计划无效");
    }

    private void validateDeleteSummary(WorkspaceFileOperationPO op,WorkspaceFileResultDTO result,List<WorkspaceFileItemsDTO.Item> items) {
        var planned=op.getDeletePlanOperationId()==null ? null : operations.get(op.getDeletePlanOperationId());
        var plan=planned==null || readResult(planned)==null ? null : readResult(planned).plan();
        var summary=result.summary();
        long deletedFiles=items.stream().filter(i -> "DELETED".equals(i.status()) && "FILE".equals(i.entryType())).count();
        long deletedDirectories=items.stream().filter(i -> "DELETED".equals(i.status()) && "DIRECTORY".equals(i.entryType())).count();
        if(plan==null || summary==null || items.size()!=plan.fileCount()+plan.directoryCount() ||
                summary.deletedFiles()!=deletedFiles || summary.deletedDirectories()!=deletedDirectories ||
                summary.remainingCount()!=items.size()-deletedFiles-deletedDirectories ||
                "SUCCEEDED".equals(result.status()) && summary.remainingCount()!=0 ||
                "PARTIAL_FAILED".equals(result.status()) && (deletedFiles+deletedDirectories==0 || summary.remainingCount()==0))
            throw invalid("删除结果范围或计数不完整，请核实状态");
    }

    private void validateItems(WorkspaceFileOperationPO op,List<WorkspaceFileItemsDTO.Item> items) {
        if(items==null || items.size()>10000) throw invalid("文件结果明细超限");
        Set<String> selected=new HashSet<>();var manifest=command(op);
        if(manifest.items()!=null) manifest.items().forEach(i -> selected.add(i.path()));
        var paths=new HashSet<String>();
        for(var item:items) {
            if(item==null) throw invalid("文件结果明细无效");protectedPath(item.path(),false);
            if(!paths.add(item.path()) || !Set.of("FILE","DIRECTORY").contains(Objects.toString(item.entryType(),"")) ||
                    !Set.of("DELETED","REMAINING","UNKNOWN","ARCHIVED","FAILED").contains(Objects.toString(item.status(),"")) ||
                    item.code()!=null && (item.code().length()>64 || !item.code().matches("[A-Z0-9_]+")) || item.error()!=null && item.error().length()>1000) throw invalid("文件结果明细无效");
            if("PREPARE_WORKSPACE_ARCHIVE".equals(op.getKind()) ? !selected.contains(item.path()) : !descendant(item.path(),op.getPath())) throw invalid("结果明细不属于请求范围");
        }
    }

    private void sendReconcile(ProjectPO p,WorkspaceFileOperationPO op,String requestKey) {
        var c=command(op);
        var reconcile=new WorkspaceFileCommandDTO(c.operationId(),c.projectId(),c.workspaceName(),c.path(),c.cursor(),c.sizeBytes(),c.sha256(),
                c.targetPath(),c.expectedRevision(),c.planId(),c.planDigest(),c.items(),c.requestDigest(),c.limits(),c.operationId());
        gateway.send(p.getDeviceCode(),new AgentCommand("RECONCILE_WORKSPACE_OPERATION",op.getId().toString(),reconcile,requestKey));
    }

    public void result(Long deviceId,WorkspaceFileResultDTO result) {
        Long id=parseId(result.operationId());
        var op=operations.get(id);
        if(op==null || !deviceId.equals(op.getDeviceId())) return;
        if(ACTIONS.contains(op.getKind())) {
            if(!Set.of("RUNNING","UNKNOWN").contains(op.getStatus())) return;
            try {completeAction(op,result);} catch(Exception e) {
                log("文件操作结果待核实",e);
                if(isMutation(op)) finish(op,"UNKNOWN","结果尚未可靠保存，请核实状态");
                else finish(op,"FAILED",shortError(e.getMessage()));
            }
            return;
        }
        if(!"RUNNING".equals(op.getStatus())) return;
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
        commandError(deviceId,operationId,null,null,message);
    }

    public void commandError(Long deviceId,String operationId,String commandType,String code,String message) {
        var op=operations.get(parseId(operationId));
        if(op!=null && deviceId.equals(op.getDeviceId())) {
            // This code is emitted only when the initial command was rejected before entering the executor.
            // A rejected read-only reconciliation says nothing about the original mutation's outcome.
            boolean rejectedBeforeExecution="AGENT_BUSY".equals(code) && Objects.equals(commandType,op.getKind()) && "RUNNING".equals(op.getStatus());
            finish(op,isMutation(op) && !"QUEUED".equals(op.getStatus()) && !rejectedBeforeExecution ? "UNKNOWN" : "FAILED",shortError(message));
        }
    }

    /** Called after a terminal transaction commits, even with no browser connected. */
    public void refreshProject(Long pid,Long uid) {if(pid!=null && uid!=null) dirtyProjects.put(pid,uid);}

    public void reconnected(Long deviceId) {
        for(var op:operations.runningForDevice(deviceId)) finish(op,isMutation(op) ? "UNKNOWN" : "FAILED","Agent 已重连，请核实上次操作结果");
        for(var op:operations.unknownForDevice(deviceId)) {
            try {
                var p=owned(op.getProjectId(),op.getUserId(),true);
                if(recoverUnsentCommand(op)) continue;
                var current=requireOperation(op.getProjectId(),op.getId());
                if("UNKNOWN".equals(current.getStatus())) {requireActionReady(p,true);sendReconcile(p,current,UUID.randomUUID().toString());}
            }
            catch(Exception e) {log("等待文件操作核实",e);}
        }
        for(var p:projects.selectForDevice(deviceId)) refreshProject(p.getId(),p.getUserId());
    }

    public void detachedAttachment(Long id) {
        if(id==null) return;
        var op=operations.get(id);
        if(op!=null) {finish(op,"FAILED","附件关联已移除");operations.detach(id);}
    }

    @Scheduled(fixedDelay=1000)
    public void dispatch() {
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
            try {
                var project=legacyProjectTransaction(op.getProjectId(),() -> {
                    if(operations.running(op.getProjectId())>0) return null;
                    var p=owned(op.getProjectId(),op.getUserId(),isWrite(op));
                    if(!online(p)) return null;
                    requireReady(p);
                    if(ACTIONS.contains(op.getKind())) requireActionReady(p,!"PREPARE_WORKSPACE_ARCHIVE".equals(op.getKind()));
                    return operations.start(op.getId())==1 ? p : null;
                });
                if(project!=null) gateway.send(project.getDeviceCode(),new AgentCommand(op.getKind(),op.getId().toString(),command(op),op.getRequestKey()));
            } catch(Exception e) {
                var current=operations.get(op.getId());
                finish(current==null ? op : current,isMutation(op) && current!=null && "RUNNING".equals(current.getStatus()) ? "UNKNOWN" : "FAILED",shortError(e.getMessage()));
            }
        }
        for(var op:operations.timedOut(LocalDateTime.now().minusMinutes(5))) {
            finish(op,isMutation(op) && "RUNNING".equals(op.getStatus()) ? "UNKNOWN" : "FAILED","操作超时，请核实结果后重试");
            if(isWrite(op)) refreshProject(op.getProjectId(),op.getUserId());
        }
    }

    @Scheduled(fixedDelay=3600000)
    public void cleanup() {
        for(var op:operations.expired(LocalDateTime.now().minusHours(24))) {
            try {
                if(op.getStorageKey()!=null) Files.deleteIfExists(storage(op.getStorageKey()));
                operations.releaseContent(op.getId());
                // Message association keeps the successful upload receipt, not a permanent byte snapshot.
                if(op.getAttachmentId()==null && op.getRequestDigest()==null) operations.expire(op.getId());
            } catch(Exception e) {log("工作区传输清理失败",e);}
        }
    }

    private WorkspaceFileOperationPO sync(ProjectPO p,String path,String cursor) {
        return legacyProjectTransaction(p.getId(),() -> {
            var previous=operations.latest(p.getId(),path,cursor);
            if(previous!=null && Set.of("QUEUED","RUNNING").contains(previous.getStatus())) {
                if("RUNNING".equals(previous.getStatus())) refreshProject(p.getId(),p.getUserId());
                return previous;
            }
            return create(p,"SYNC_WORKSPACE_TREE",path,cursor,UUID.randomUUID().toString(),null,0,null,null);
        });
    }

    private WorkspaceFileOperationPO create(ProjectPO p,String kind,String path,String cursor,String requestKey,
            String storageKey,long size,String sha,Long attachmentId) {
        return legacyProjectTransaction(p.getId(),() -> createLocked(p,kind,path,cursor,requestKey,storageKey,size,sha,attachmentId));
    }
    private WorkspaceFileOperationPO createLocked(ProjectPO p,String kind,String path,String cursor,String requestKey,
            String storageKey,long size,String sha,Long attachmentId) {
        try {UUID.fromString(requestKey);} catch(Exception e) {throw invalid("请求标识无效");}
        var existing=operations.request(p.getId(),requestKey);
        if(existing!=null) {
            if(!existing.getKind().equals(kind) || !existing.getPath().equals(path) || !Objects.equals(existing.getSha256(),sha) || existing.getSizeBytes()!=size)
                throw new BusinessException(ErrorCode.CONFLICT,"请求标识已用于其他文件操作");
            return existing;
        }
        if(operations.activeCount(p.getId())>=100) throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,"工作区待处理操作过多");
        if(!ACTIONS.contains(kind) && !"SYNC_WORKSPACE_TREE".equals(kind)) assertNoMutation(p.getId());
        var op=new WorkspaceFileOperationPO();
        op.setRequestKey(requestKey);op.setUserId(p.getUserId());op.setProjectId(p.getId());op.setDeviceId(p.getDeviceId());
        op.setWorkspaceName(p.getWorkspaceName());op.setKind(kind);op.setPath(path);op.setCursor(cursor);
        op.setStorageKey(storageKey);op.setSizeBytes(size);op.setSha256(sha);op.setAttachmentId(attachmentId);op.setStatus("QUEUED");
        operations.insert(op);return op;
    }

    private WorkspaceFileOperationPO agentOperation(Long id,String code,String auth) {
        var device=authentication.authenticate(code,auth);var op=operations.get(id);
        if(op==null || !device.getId().equals(op.getDeviceId()) || !("RUNNING".equals(op.getStatus()) || isMutation(op) && "UNKNOWN".equals(op.getStatus()))) throw missing();
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
    private WorkspaceFileCapabilitiesVO capabilities(ProjectPO p) {
        var d=devices.selectById(p.getDeviceId());
        boolean mutation=supported(p) && d!=null && Boolean.TRUE.equals(d.getWorkspaceFileMutations());
        boolean archive=supported(p) && d!=null && Boolean.TRUE.equals(d.getWorkspaceArchiveDownload());
        String unavailable=!online(p) ? "Agent 离线" : !"ENABLED".equals(p.getWorkspaceStatus()) ? "工作区尚未就绪" : null;
        boolean occupied=operations.mutationCount(p.getId())>0;
        String mutationReason=unavailable!=null ? unavailable : occupied ? "文件修改处理中或结果待核实" : experts!=null && experts.activeTurns(p.getId())>0 ? "请结束项目中的任务后再修改文件" : null;
        String archiveReason=unavailable!=null ? unavailable : occupied ? "文件修改处理中或结果待核实" : null;
        return new WorkspaceFileCapabilitiesVO(new WorkspaceFileCapabilitiesVO.Capability(mutation,mutation && mutationReason==null,mutation ? mutationReason : "请升级支持安全文件修改的 Agent"),
                new WorkspaceFileCapabilitiesVO.Capability(archive,archive && archiveReason==null,archive ? archiveReason : "请升级 Agent 以支持 ZIP 下载"));
    }
    private WorkspaceFileLimitsVO limits(ProjectPO p) {
        var effective=effectiveLimits(p);
        return new WorkspaceFileLimitsVO(effective.maxFiles(),effective.maxTotalBytes(),effective.maxOutputBytes(),effective.maxFileBytes(),effective.maxRequestBytes());
    }
    private WorkspaceFileCommandDTO.Limits effectiveLimits(ProjectPO p) {
        var local=new WorkspaceFileCommandDTO.Limits(properties.getMaxArchiveFiles(),properties.getMaxFileBytes(),properties.getMaxArchiveSourceBytes(),properties.getMaxArchiveOutputBytes(),MAX_REQUEST_BYTES,300);
        var d=devices.selectById(p.getDeviceId());
        if(d==null || d.getWorkspaceFileLimits()==null) return local;
        try {
            var remote=json.readValue(d.getWorkspaceFileLimits(),WorkspaceFileCommandDTO.Limits.class);
            if(remote.maxFiles()<1 || remote.maxFileBytes()<1 || remote.maxTotalBytes()<1 || remote.maxOutputBytes()<1 || remote.maxRequestBytes()<1 || remote.maxDurationSeconds()<1) throw invalid("Agent 文件限制无效，请重新连接");
            return new WorkspaceFileCommandDTO.Limits(Math.min(local.maxFiles(),remote.maxFiles()),Math.min(local.maxFileBytes(),remote.maxFileBytes()),
                    Math.min(local.maxTotalBytes(),remote.maxTotalBytes()),Math.min(local.maxOutputBytes(),remote.maxOutputBytes()),
                    Math.min(local.maxRequestBytes(),remote.maxRequestBytes()),Math.min(local.maxDurationSeconds(),remote.maxDurationSeconds()));
        } catch(IOException e) {throw invalid("Agent 文件限制无效，请重新连接");}
    }
    private void requireActionReady(ProjectPO p,boolean mutation) {
        requireReady(p);var d=devices.selectById(p.getDeviceId());
        if(d==null || !Boolean.TRUE.equals(mutation ? d.getWorkspaceFileMutations() : d.getWorkspaceArchiveDownload()))
            throw new BusinessException(ErrorCode.CONFLICT,mutation ? "请升级支持安全文件修改的 Agent" : "请升级 Agent 以支持 ZIP 下载");
    }
    private void requireReady(ProjectPO p) {
        if(!supported(p)) throw new BusinessException(ErrorCode.CONFLICT,"请升级 Agent 以支持工作区文件管理");
        if(!online(p)) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        if(!"ENABLED".equals(p.getWorkspaceStatus())) throw new BusinessException(ErrorCode.CONFLICT,"工作区尚未就绪");
    }
    private static boolean isWrite(WorkspaceFileOperationPO op) {return Set.of("UPLOAD_WORKSPACE_FILE","CREATE_WORKSPACE_DIRECTORY","RELOCATE_WORKSPACE_ENTRY","PREPARE_WORKSPACE_DELETE","DELETE_WORKSPACE_ENTRY").contains(op.getKind());}
    private WorkspaceFileCommandDTO command(WorkspaceFileOperationPO op) {
        if(op.getPayloadJson()!=null) {
            try {return json.readValue(op.getPayloadJson(),WorkspaceFileCommandDTO.class);}
            catch(IOException e) {throw new IllegalStateException("Corrupt workspace command",e);}
        }
        // Download size/hash change as bytes arrive; the authorization manifest remains stable.
        boolean upload="UPLOAD_WORKSPACE_FILE".equals(op.getKind());
        return new WorkspaceFileCommandDTO(op.getId().toString(),op.getProjectId().toString(),op.getWorkspaceName(),op.getPath(),op.getCursor(),upload ? op.getSizeBytes() : 0,upload ? op.getSha256() : null);
    }
    private void finish(WorkspaceFileOperationPO op,String status,String error) {
        if(ACTIONS.contains(op.getKind())) {
            if(!Set.of("QUEUED","RUNNING","UNKNOWN").contains(op.getStatus())) return;
            completeAction(op,new WorkspaceFileResultDTO(op.getId().toString(),false,error,List.of(),null,0,0,null,1,status,
                    "UNKNOWN".equals(status) ? "UNKNOWN" : "NO_CHANGE","UNKNOWN".equals(status) ? "RESULT_UNKNOWN" : "COMMAND_FAILED",
                    op.getPath(),op.getTargetPath(),null,null,null,null,null,null));
        } else freshResultTransaction(() -> {
            if(operations.finish(op.getId(),status,error)==1) afterCommit(() -> emit(op,status,null));
            return null;
        });
    }
    private void emit(WorkspaceFileOperationPO op,String status,WorkspaceFileResultDTO result) {
        try {
            var dirs=new TreeSet<String>();dirs.add(parent(op.getPath()));
            if(op.getTargetPath()!=null) dirs.add(parent(op.getTargetPath()));
            events.sendToUser(op.getUserId(),new WorkspaceFilesChangedVO("WORKSPACE_FILES_CHANGED",op.getDeviceId(),
                    new WorkspaceFilesChangedVO.Payload(op.getProjectId().toString(),op.getPath(),op.getId().toString(),op.getKind(),status,
                            op.getPath(),op.getTargetPath(),result==null ? null : result.entryType(),result==null ? null : result.entryRevision(),List.copyOf(dirs))));
        } catch(Exception e) {log("文件变更通知失败，可从近期操作恢复",e);}
    }
    private void invalidate(Long pid) {
        try {redis.delete(cacheKey(pid));redis.delete(pathsKey(pid));}
        catch(Exception e) {log("目录缓存清理失败，将继续刷新",e);}
    }
    private void afterCommit(Runnable work) {
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {work.run();}
        }); else work.run();
    }
    private <T> T legacyProjectTransaction(Long pid,Supplier<T> work) {
        // Production always has coordination injected. This preserves existing direct-construction read tests.
        return transactions==null ? work.get() : projectTransaction(pid,work);
    }
    private <T> T freshResultTransaction(Supplier<T> work) {
        return resultTransactions==null ? work.get() : resultTransactions.execute(tx -> work.get());
    }
    private <T> T resultProjectTransaction(Long pid,Supplier<T> work) {
        if(resultTransactions==null || experts==null || attachmentLocations==null) throw new IllegalStateException("Workspace action coordination is not configured");
        // Agent control events call us after their transaction commits; REQUIRED would join an already committed transaction.
        return resultTransactions.execute(tx -> {if(experts.lockProject(pid)==null) throw missing();return work.get();});
    }
    private static boolean isMutation(WorkspaceFileOperationPO op) {return MUTATIONS.contains(Objects.toString(op.getKind(),""));}
    private String encode(Object value) {
        try {return json.writeValueAsString(value);} catch(IOException e) {throw new IllegalStateException("Cannot encode workspace operation",e);}
    }
    private WorkspaceFileResultDTO readResult(WorkspaceFileOperationPO op) {
        if(op.getResultJson()==null) return null;
        try {return json.readValue(op.getResultJson(),WorkspaceFileResultDTO.class);} catch(IOException e) {throw new IllegalStateException("Corrupt workspace result",e);}
    }
    private static String digest(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));} catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private static void protectedPath(String path,boolean rootAllowed) {
        validatePath(path);
        if(!rootAllowed && path.isEmpty()) throw invalid("不能操作工作区根目录");
        if(!visiblePath(path)) throw invalid("内部文件和目录不可操作");
    }
    private static void revision(String value) {if(value==null || value.isBlank() || value.length()>1024) throw invalid("文件版本缺失或无效，请刷新目录");}
    private static void requireUuid(String value) {try {if(value==null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();} catch(Exception e) {throw invalid("请求或计划标识无效");}}
    private static long nonnegativeId(String text) {try {long n=Long.parseLong(text);if(n<0) throw new IllegalArgumentException();return n;} catch(Exception e) {throw invalid("分页游标无效");}}
    private static long positiveId(String text) {long n=nonnegativeId(text);if(n==0) throw invalid("分页游标无效");return n;}
    private static void pageLimit(int limit) {if(limit<1 || limit>200) throw invalid("分页大小无效");}
    private static String parent(String path) {int slash=path.lastIndexOf('/');return slash<0 ? "" : path.substring(0,slash);}
    private static String join(String parent,String name) {String path=parent.isEmpty() ? name : parent+"/"+name;protectedPath(path,false);return path;}
    private static boolean descendant(String path,String source) {return path.equals(source) || path.startsWith(source+"/");}
    private static String shortNullable(String value) {return value==null ? null : shortError(value);}
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
    private WorkspaceFileContentVO contentFile(WorkspaceFileOperationPO op) throws IOException {
        Path path=storage(op.getStorageKey());
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=op.getSizeBytes()) throw missing();
        return new WorkspaceFileContentVO(new FileSystemResource(path),op.getPath().substring(op.getPath().lastIndexOf('/')+1),op.getSizeBytes());
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
