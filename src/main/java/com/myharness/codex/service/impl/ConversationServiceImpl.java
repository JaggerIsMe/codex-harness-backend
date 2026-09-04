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
    private final ConversationMapper conversationMapper;
    private final AgentDeviceMapper deviceMapper;
    private final AgentCommandGateway gateway;
    private final TransactionTemplate transactions;
    private final ApprovalMapper approvalMapper;
    private final ObjectMapper objectMapper;
    private final ProjectMapper projectMapper;
    private final ConversationMessageStream streams;

    public ConversationServiceImpl(ConversationMapper conversationMapper,AgentDeviceMapper deviceMapper,
                                   AgentCommandGateway gateway,TransactionTemplate transactions,
                                   ApprovalMapper approvalMapper,ObjectMapper objectMapper,ProjectMapper projectMapper,ConversationMessageStream streams) {
        this.conversationMapper=conversationMapper; this.deviceMapper=deviceMapper; this.gateway=gateway; this.transactions=transactions;
        this.approvalMapper=approvalMapper; this.objectMapper=objectMapper;
        this.projectMapper=projectMapper;
        this.streams=streams;
    }

    @Override public ConversationVO createConversation(Long projectId,CreateConversationDTO dto,Long operatorId) {
        ProjectPO project=requireActiveProject(projectId,operatorId);
        AgentDevicePO device=requireOnline(project.getDeviceId());
        AgentWorkspacePO workspace=deviceMapper.selectWorkspace(project.getWorkspaceId(),device.getId());
        if (workspace==null || !"ENABLED".equals(workspace.getStatus())) throw new BusinessException(ErrorCode.CONFLICT,"项目执行目录当前不可用");
        ConversationPO conversation=transactions.execute(status -> {
            ConversationPO value=new ConversationPO();
            value.setUserId(operatorId); value.setDeviceId(device.getId()); value.setWorkspaceId(workspace.getId());
            value.setProjectId(project.getId());
            value.setTitle(trimOr(dto.getTitle(),"新会话")); value.setStatus("ACTIVE"); value.setLastActivityAt(LocalDateTime.now());
            conversationMapper.insertConversation(value); return value;
        });
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("projectId",String.valueOf(project.getId())); payload.put("conversationId",String.valueOf(conversation.getId())); payload.put("workspaceName",workspace.getWorkspaceName());
        payload.put("model",trimOr(dto.getModel(),null));
        try { gateway.send(device.getDeviceCode(),new AgentCommand("START_THREAD",String.valueOf(conversation.getId()),payload)); }
        catch (RuntimeException exception) {
            conversationMapper.failConversation(conversation.getId(),device.getId(),LocalDateTime.now());
            throw exception;
        }
        return new ConversationVO(conversationMapper.selectConversation(conversation.getId()));
    }

    @Override public TurnVO startTurn(Long projectId,Long conversationId,StartTurnDTO dto,Long operatorId) {
        ConversationPO conversation=requireOwned(projectId,conversationId,operatorId);
        if (conversation.getCodexThreadId()==null) throw new BusinessException(ErrorCode.CONFLICT,"Agent 尚未完成会话初始化");
        requireOnline(conversation.getDeviceId());
        final ConversationTurnPO turn;
        try {
            turn=transactions.execute(status -> {
                ConversationPO locked=conversationMapper.lockConversation(conversationId);
                ConversationTurnPO value=new ConversationTurnPO(); value.setConversationId(conversationId); value.setStatus("CREATED");
                conversationMapper.insertTurn(value);
                long sequence=conversationMapper.nextSequence(conversationId);
                conversationMapper.insertMessage(conversationId,value.getId(),sequence,"USER","TEXT",dto.getMessage());
                return value;
            });
        } catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.CONFLICT,"当前会话已有活动任务"); }
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("conversationId",String.valueOf(conversationId)); payload.put("turnId",String.valueOf(turn.getId()));
        payload.put("projectId",String.valueOf(conversation.getProjectId()));
        payload.put("workspaceName",conversation.getWorkspaceName());
        payload.put("codexThreadId",conversation.getCodexThreadId());
        payload.put("message",dto.getMessage()); payload.put("model",trimOr(dto.getModel(),null));
        payload.put("reasoningEffort",trimOr(dto.getReasoningEffort(),null));
        try { gateway.send(conversation.getDeviceCode(),new AgentCommand("START_TURN",String.valueOf(turn.getId()),payload)); }
        catch (RuntimeException exception) {
            conversationMapper.finishTurn(turn.getId(),conversationId,conversation.getDeviceId(),"FAILED","AGENT_OFFLINE",
                    "Agent command could not be delivered",LocalDateTime.now()); throw exception;
        }
        return new TurnVO(conversationMapper.selectTurn(turn.getId()));
    }

    @Override public void interruptTurn(Long projectId,Long conversationId,Long turnId,Long operatorId) {
        ConversationPO conversation=requireOwned(projectId,conversationId,operatorId); requireOnline(conversation.getDeviceId());
        ConversationTurnPO turn=conversationMapper.selectTurn(turnId);
        if (turn==null || !conversationId.equals(turn.getConversationId()) || !("RUNNING".equals(turn.getStatus()) || "WAITING_APPROVAL".equals(turn.getStatus())))
            throw new BusinessException(ErrorCode.CONFLICT,"任务当前不可中断");
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("conversationId",String.valueOf(conversationId)); payload.put("turnId",String.valueOf(turnId));
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
        return streams.state(conversationId,0,200,null,-1).getMessages();
    }

    @Override public MessageStateVO getMessageState(Long projectId,Long conversationId,Long operatorId,long before,int limit,Long turnId,long after) {
        requireOwned(projectId,conversationId,operatorId);
        if(before<0 || after < -1 || limit<1 || limit>200) throw new IllegalArgumentException("Invalid message cursor or page size");
        return streams.state(conversationId,before,limit,turnId,after);
    }

    @Override public List<ApprovalVO> getApprovals(Long projectId,Long conversationId,Long operatorId) {
        requireOwned(projectId,conversationId,operatorId);
        return approvalMapper.selectByConversation(conversationId).stream().map(value -> new ApprovalVO(value,objectMapper)).collect(Collectors.toList());
    }

    private ConversationPO requireOwned(Long projectId,Long id,Long userId) {
        ConversationPO value=conversationMapper.selectOwnedConversation(projectId,id,userId);
        if (value==null) throw new BusinessException(ErrorCode.NOT_FOUND,"会话不存在");
        return value;
    }
    private ProjectPO requireActiveProject(Long projectId,Long userId) {
        ProjectPO value=projectMapper.selectOwned(projectId,userId);
        if (value==null) throw new BusinessException(ErrorCode.NOT_FOUND,"项目不存在");
        if (!"ACTIVE".equals(value.getStatus())) throw new BusinessException(ErrorCode.CONFLICT,"项目当前不可执行");
        return value;
    }
    private AgentDevicePO requireOnline(Long deviceId) {
        AgentDevicePO device=deviceMapper.selectById(deviceId);
        if (device==null || "DISABLED".equals(device.getStatus())) throw new BusinessException(ErrorCode.NOT_FOUND,"设备不存在或已禁用");
        if (!gateway.isOnline(device.getDeviceCode())) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        return device;
    }
    private String trimOr(String value,String fallback) { return value==null || value.trim().isEmpty() ? fallback : value.trim(); }
}
