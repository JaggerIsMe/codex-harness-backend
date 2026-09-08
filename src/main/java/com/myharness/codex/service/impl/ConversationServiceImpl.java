package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.CreateConversationDTO;
import com.myharness.codex.entity.dto.StartTurnDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.AgentWorkspacePO;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.entity.po.ConversationTurnPO;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.entity.vo.ConversationVO;
import com.myharness.codex.entity.vo.TurnVO;
import com.myharness.codex.entity.vo.ConversationMessageVO;
import com.myharness.codex.entity.vo.ApprovalVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommand;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.ProjectMapper;
import com.myharness.codex.mapper.ApprovalMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.service.ConversationService;
import com.myharness.codex.service.stream.ConversationMessageStream;
import com.myharness.codex.entity.vo.MessageStateVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationServiceImpl implements ConversationService {
    private com.myharness.codex.service.WorkspaceFileService workspaceFiles;
    @org.springframework.beans.factory.annotation.Autowired
    public void setWorkspaceFiles(com.myharness.codex.service.WorkspaceFileService value) {workspaceFiles=value;}
    private void refreshFiles(ConversationPO c) {
        if(workspaceFiles==null) return;
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {workspaceFiles.refreshProject(c.getProjectId(),c.getUserId());}
            });
        } else workspaceFiles.refreshProject(c.getProjectId(),c.getUserId());
    }
    private final com.myharness.codex.service.ExpertService experts;
    private final com.myharness.codex.service.ModelConfigurationService models;
    private final ConversationMapper conversationMapper;
    private final com.myharness.codex.service.ConversationAttachmentService attachments;
    private final AgentDeviceMapper deviceMapper;
    private final AgentCommandGateway gateway;
    private final TransactionTemplate transactions;
    private final ApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper;
    private final ProjectMapper projectMapper;
    private final ConversationMessageStream streams;
    private final com.myharness.codex.security.AuthorizationService access;

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationServiceImpl(ConversationMapper conversationMapper,AgentDeviceMapper deviceMapper,
                                   AgentCommandGateway gateway,TransactionTemplate transactions,
                                   ApprovalMapper approvalMapper,ObjectMapper objectMapper,ProjectMapper projectMapper,ConversationMessageStream streams,
                                   com.myharness.codex.security.AuthorizationService access, com.myharness.codex.service.ConversationAttachmentService attachments,
                                   com.myharness.codex.service.ExpertService experts,com.myharness.codex.service.ModelConfigurationService models) {
        this.experts=experts;
        this.models=models;
        this.conversationMapper=conversationMapper; this.deviceMapper=deviceMapper; this.gateway=gateway; this.transactions=transactions;
        this.approvalMapper=approvalMapper; this.objectMapper=objectMapper;
        this.projectMapper=projectMapper;
        this.streams=streams;
        this.access=access; this.attachments=attachments;
    }

    ConversationServiceImpl(ConversationMapper conversationMapper,AgentDeviceMapper deviceMapper,
        AgentCommandGateway gateway,TransactionTemplate transactions,ApprovalMapper approvalMapper,ObjectMapper objectMapper,
        ProjectMapper projectMapper,ConversationMessageStream streams,com.myharness.codex.security.AuthorizationService access,
        com.myharness.codex.service.ConversationAttachmentService attachments,com.myharness.codex.service.ExpertService experts) {
        this(conversationMapper,deviceMapper,gateway,transactions,approvalMapper,objectMapper,projectMapper,streams,access,attachments,experts,null);
    }

    @Override public ConversationVO createConversation(Long projectId,CreateConversationDTO dto,Long operatorId) {
        access.requirePermission(operatorId,"conversation:create");
        ProjectPO project=requireActiveProject(projectId,operatorId);
        AgentDevicePO device=requireOnline(project.getDeviceId());
        var modelRuntime=models==null?null:models.runtimeForDevice(device.getId());
        AgentWorkspacePO workspace=deviceMapper.selectWorkspace(project.getWorkspaceId(),device.getId());
        if (workspace==null || !"ENABLED".equals(workspace.getStatus())) throw new BusinessException(ErrorCode.CONFLICT,"项目执行目录当前不可用");
        ConversationPO conversation=transactions.execute(status -> {
            experts.lockProject(projectId);
            ConversationPO value=new ConversationPO();
            value.setUserId(operatorId); value.setDeviceId(device.getId()); value.setWorkspaceId(workspace.getId());
            value.setProjectId(project.getId());
            value.setTitle(trimOr(dto.getTitle(),"新会话")); value.setStatus("ACTIVE"); value.setLastActivityAt(LocalDateTime.now());
            if(modelRuntime!=null)value.setModelRuntimeKey(modelRuntime.getRuntimeKey());
            experts.bindAtCreation(value,dto.getExpertId());
            conversationMapper.insertConversation(value); return value;
        });
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("projectId",String.valueOf(project.getId())); payload.put("conversationId",String.valueOf(conversation.getId())); payload.put("workspaceName",workspace.getWorkspaceName());
        if(modelRuntime!=null)payload.put("modelRuntime",modelRuntime);
        try { gateway.send(device.getDeviceCode(),new AgentCommand("START_THREAD",String.valueOf(conversation.getId()),payload)); }
        catch (RuntimeException exception) {
            conversationMapper.failConversation(conversation.getId(),device.getId(),LocalDateTime.now());
            throw exception;
        }
        return new ConversationVO(conversationMapper.selectConversation(conversation.getId()));
    }

    @Override public TurnVO startTurn(Long projectId,Long conversationId,StartTurnDTO dto,Long operatorId) {
        access.requirePermission(operatorId,"turn:start");
        requireActiveProject(projectId,operatorId);
        ConversationPO conversation=requireOwned(projectId,conversationId,operatorId);
        if (conversation.getCodexThreadId()==null) throw new BusinessException(ErrorCode.CONFLICT,"Agent 尚未完成会话初始化");
        if ((dto.getMessage()==null || dto.getMessage().isBlank()) && dto.getAttachmentIds().isEmpty())
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"消息和附件不能同时为空");
        String fingerprint;
        try {fingerprint=com.myharness.codex.security.SecureDigests.sha256(objectMapper.writeValueAsString(dto));}
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalArgumentException("Invalid Turn input",e);}
        final String requestHash=fingerprint;
        java.util.concurrent.atomic.AtomicBoolean created=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> threadModelRuntimeKey=new java.util.concurrent.atomic.AtomicReference<>(conversation.getModelRuntimeKey());
        final ConversationTurnPO turn;
        try {
            turn=transactions.execute(status -> {
                Long projectRevision=experts.lockProject(projectId);
                ConversationPO locked=conversationMapper.lockConversation(conversationId);
                threadModelRuntimeKey.set(locked.getModelRuntimeKey());
                if(dto.getClientRequestId()!=null) {
                    ConversationTurnPO previous=conversationMapper.byClientRequest(conversationId,dto.getClientRequestId());
                    if(previous!=null) {
                        if(!requestHash.equals(previous.getRequestHash())) throw new BusinessException(ErrorCode.CONFLICT,"发送标识已用于不同内容");
                        return previous;
                    }
                }
                AgentDevicePO online=requireOnline(conversation.getDeviceId());
                if(!dto.getAttachmentIds().isEmpty() && !Boolean.TRUE.equals(online.getConversationAttachments()))
                    throw new BusinessException(ErrorCode.CONFLICT,"请升级 Agent 以支持会话附件");
                ConversationTurnPO value=new ConversationTurnPO(); value.setConversationId(conversationId); value.setStatus("CREATED");
                var runtime=experts.freeze(locked,projectRevision);
                value.setExpertVersionId(runtime.getExpertVersionId()); value.setExpertName(runtime.getName());
                value.setExpertRuntime(experts.write(runtime));
                if(models!=null){var model=models.runtimeForDevice(locked.getDeviceId());
                    value.setModelConfigurationVersionId(model.getConfigurationVersionId());value.setModelName(model.getName());
                    value.setModelRuntime(models.snapshot(model));}
                value.setClientRequestId(dto.getClientRequestId()); value.setRequestHash(requestHash);
                value.setPreparationPhase(dto.getAttachmentIds().isEmpty() ? null : "DOWNLOADING");
                if(!runtime.getSkills().isEmpty()) value.setPreparationPhase("EXPERT_SKILLS");
                conversationMapper.insertTurn(value);
                long sequence=conversationMapper.nextSequence(conversationId);
                conversationMapper.insertMessage(conversationId,value.getId(),sequence,"USER","TEXT",dto.getMessage()==null ? "" : dto.getMessage());
                attachments.bind(locked,value.getId(),dto.getAttachmentIds());
                created.set(true);
                return value;
            });
        } catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.CONFLICT,"当前会话已有活动任务"); }
        if(!created.get()) return new TurnVO(turn);
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("conversationId",String.valueOf(conversationId)); payload.put("turnId",String.valueOf(turn.getId()));
        payload.put("projectId",String.valueOf(conversation.getProjectId()));
        payload.put("workspaceName",conversation.getWorkspaceName());
        payload.put("codexThreadId",conversation.getCodexThreadId());
        payload.put("threadRuntimeKey",conversation.getExpertRuntimeKey());
        payload.put("threadModelRuntimeKey",threadModelRuntimeKey.get());
        var frozenRuntime=experts.runtime(turn.getExpertRuntime());
        payload.put("recreateUnstartedThread",frozenRuntime!=null && frozenRuntime.getSchemaVersion()>=2
                ? conversationMapper.canRecreateExpertThread(conversationId,turn.getId(),frozenRuntime.getRuntimeKey())
                : conversationMapper.canRecreateUnstartedThread(conversationId,turn.getId()));
        if(!dto.getAttachmentIds().isEmpty()) payload.put("attachments",attachments.forTurn(turn.getId()));
        payload.put("message",dto.getMessage()==null ? "" : dto.getMessage());
        if(models!=null)payload.put("modelRuntime",models.runtimeForSnapshot(turn.getModelRuntime()));
        payload.put("expertRuntime",frozenRuntime);
        try { gateway.send(conversation.getDeviceCode(),new AgentCommand("START_TURN",String.valueOf(turn.getId()),payload)); }
        catch (RuntimeException exception) {
            conversationMapper.finishTurn(turn.getId(),conversationId,conversation.getDeviceId(),"FAILED","AGENT_OFFLINE",
                    "Agent command could not be delivered",LocalDateTime.now()); refreshFiles(conversation);throw exception;
        }
        return new TurnVO(conversationMapper.selectTurn(turn.getId()));
    }

    @Override public void interruptTurn(Long projectId,Long conversationId,Long turnId,Long operatorId) {
        access.requirePermission(operatorId,"turn:interrupt");
        ConversationPO conversation=requireOwned(projectId,conversationId,operatorId); requireOnline(conversation.getDeviceId());
        ConversationTurnPO turn=conversationMapper.selectTurn(turnId);
        if (turn==null || !conversationId.equals(turn.getConversationId()) || !("CREATED".equals(turn.getStatus()) || "RUNNING".equals(turn.getStatus()) || "WAITING_APPROVAL".equals(turn.getStatus())))
            throw new BusinessException(ErrorCode.CONFLICT,"任务当前不可中断");
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("conversationId",String.valueOf(conversationId)); payload.put("turnId",String.valueOf(turnId));
        if("CREATED".equals(turn.getStatus())) {
            conversationMapper.finishTurn(turnId,conversationId,conversation.getDeviceId(),"INTERRUPTED",null,"用户取消文件准备",LocalDateTime.now());
            refreshFiles(conversation);
        }
        gateway.send(conversation.getDeviceCode(),new AgentCommand("INTERRUPT_TURN",String.valueOf(turnId),payload));
    }

    @Override public ConversationVO getConversation(Long projectId,Long conversationId,Long operatorId) { return new ConversationVO(requireOwned(projectId,conversationId,operatorId)); }

    @Override public List<ConversationVO> getProjectConversations(Long projectId,Long operatorId) {
        requireActiveProject(projectId,operatorId);
        return conversationMapper.selectProjectConversations(projectId,operatorId).stream().map(ConversationVO::new).collect(Collectors.toList());
    }

    @Override public TurnVO getActiveTurn(Long projectId,Long conversationId,Long operatorId) {
        requireOwned(projectId,conversationId,operatorId);
        ConversationTurnPO turn=conversationMapper.selectActiveTurn(conversationId);
        return turn==null ? null : new TurnVO(turn);
    }

    @Override public List<ConversationMessageVO> getMessages(Long projectId,Long conversationId,Long operatorId) {
        requireOwned(projectId,conversationId,operatorId);
        List<ConversationMessageVO> messages=streams.state(conversationId,0,200,null,-1).getMessages();
        attachments.enrich(messages); return messages;
    }

    @Override public MessageStateVO getMessageState(Long projectId,Long conversationId,Long operatorId,long before,int limit,Long turnId,long after) {
        requireOwned(projectId,conversationId,operatorId);
        if(before<0 || after < -1 || limit<1 || limit>200) throw new IllegalArgumentException("Invalid message cursor or page size");
        MessageStateVO result=streams.state(conversationId,before,limit,turnId,after);
        attachments.enrich(result.getMessages()); return result;
    }

    @Override public List<ApprovalVO> getApprovals(Long projectId,Long conversationId,Long operatorId) {
        requireOwned(projectId,conversationId,operatorId);
        return approvalMapper.selectByConversation(conversationId).stream().map(value -> new ApprovalVO(value,objectMapper)).collect(Collectors.toList());
    }

    private ConversationPO requireOwned(Long projectId,Long id,Long userId) {
        access.requirePermission(userId,"conversation:read");
        ConversationPO value=conversationMapper.selectOwnedConversation(projectId,id,userId);
        if (value==null) throw new BusinessException(ErrorCode.NOT_FOUND,"会话不存在");
        access.requireDevice(userId,value.getDeviceId());
        return value;
    }
    private ProjectPO requireActiveProject(Long projectId,Long userId) {
        access.requirePermission(userId,"conversation:read");
        ProjectPO value=projectMapper.selectOwned(projectId,userId);
        if (value==null) throw new BusinessException(ErrorCode.NOT_FOUND,"项目不存在");
        access.requireDevice(userId,value.getDeviceId());
        if (!"ACTIVE".equals(value.getStatus())) throw new BusinessException(ErrorCode.CONFLICT,"项目当前不可执行");
        if (!"ENABLED".equals(value.getWorkspaceStatus()) || value.getRootPath()==null)
            throw new BusinessException(ErrorCode.CONFLICT,"项目执行目录尚未就绪");
        return value;
    }
    private AgentDevicePO requireOnline(Long deviceId) {
        AgentDevicePO device=deviceMapper.selectById(deviceId);
        if (device==null || "DISABLED".equals(device.getStatus())) throw new BusinessException(ErrorCode.NOT_FOUND,"设备不存在或已禁用");
        if (!"WINDOWS_PROJECT_PROFILE".equals(device.getIsolationMode()))
            throw new BusinessException(ErrorCode.CONFLICT,"请升级 Agent 以启用项目读取隔离");
        if (!gateway.isOnline(device.getDeviceCode())) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        return device;
    }
    private String trimOr(String value,String fallback) { return value==null || value.trim().isEmpty() ? fallback : value.trim(); }
}
