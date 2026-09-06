package com.myharness.codex.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.myharness.codex.entity.dto.AgentProtocolEnvelope;
import com.myharness.codex.entity.enums.AgentEventType;
import com.myharness.codex.entity.po.AgentWorkspacePO;
import com.myharness.codex.entity.po.AgentWorkspaceRootPO;
import com.myharness.codex.entity.po.ApprovalRequestPO;
import com.myharness.codex.entity.po.ConversationPO;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ApprovalMapper;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.SkillMapper;
import com.myharness.codex.security.SecureDigests;
import com.myharness.codex.service.AgentEventService;
import com.myharness.codex.service.stream.ConversationMessageStream;
import org.springframework.transaction.support.TransactionTemplate;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentEventServiceImpl implements AgentEventService {
    private final AgentDeviceMapper deviceMapper;
    private final ConversationMapper conversationMapper;
    private final ApprovalMapper approvalMapper;
    private final SkillMapper skillMapper;
    private final ClientEventWebSocketHandler clientEvents;
    private final ConversationMessageStream streams;
    private final TransactionTemplate transactions;

    public AgentEventServiceImpl(AgentDeviceMapper deviceMapper, ConversationMapper conversationMapper,
                                 ApprovalMapper approvalMapper, SkillMapper skillMapper,
                                 ClientEventWebSocketHandler clientEvents,ConversationMessageStream streams,TransactionTemplate transactions) {
        this.deviceMapper=deviceMapper; this.conversationMapper=conversationMapper;
        this.approvalMapper=approvalMapper; this.skillMapper=skillMapper;
        this.clientEvents=clientEvents;
        this.streams=streams; this.transactions=transactions;
    }

    @Override
    public boolean process(Long deviceId, AgentProtocolEnvelope envelope) {
        if ("TURN_EVENT".equals(envelope.getType())) return streams.accept(deviceId,envelope.getPayload());
        return Boolean.TRUE.equals(transactions.execute(status -> processControl(deviceId,envelope)));
    }

    private boolean processControl(Long deviceId, AgentProtocolEnvelope envelope) {
        AgentEventType type = AgentEventType.valueOf(envelope.getType());
        if (type!=AgentEventType.HEARTBEAT && type!=AgentEventType.PONG &&
                deviceMapper.insertEvent(deviceId,envelope.getMessageId(),type.name(),envelope.getTimestamp()) != 1) return false;
        JsonNode payload = envelope.getPayload();
        LocalDateTime now = LocalDateTime.now();
        switch (type) {
            case REGISTER:
                deviceMapper.attachmentCapability(deviceId,payload.path("capabilities").isArray() && java.util.stream.StreamSupport.stream(payload.path("capabilities").spliterator(),false).anyMatch(v -> "CONVERSATION_ATTACHMENTS_V1".equals(v.asText())));
                deviceMapper.expertCapability(deviceId,payload.path("capabilities").isArray() && java.util.stream.StreamSupport.stream(payload.path("capabilities").spliterator(),false).anyMatch(v -> "CONVERSATION_EXPERTS_V3".equals(v.asText())));
                deviceMapper.updateRegistration(deviceId,text(payload,"deviceName",128),text(payload,"agentVersion",64),
                        text(payload,"osName",128),optionalText(payload,"osVersion",128),
                        optionalText(payload,"isolationMode",32)==null ? "UNKNOWN" : optionalText(payload,"isolationMode",32),now);
                mergeWorkspaces(deviceId,payload.get("workspaces"),now);
                mergeWorkspaceRoots(deviceId,payload.get("workspaceRoots"),now);
                break;
            case HEARTBEAT: deviceMapper.heartbeat(deviceId,now); break;
            case WORKSPACES_CHANGED: mergeWorkspaces(deviceId,payload,now); break;
            case THREAD_STARTED: threadStarted(deviceId,payload,now); break;
            case EXPERT_RUNTIME_UPDATED: expertRuntimeUpdated(deviceId,payload,now); break;
            case TURN_STARTED: turnStarted(deviceId,payload,now); break;
            case APPROVAL_REQUIRED: approvalRequired(deviceId,payload); break;
            case APPROVAL_RESOLVED: approvalResolved(deviceId,payload); break;
            case TURN_COMPLETED: terminal(deviceId,payload,"COMPLETED",null,now); break;
            case TURN_FAILED: terminal(deviceId,payload,"FAILED","COMMAND_FAILED",now); break;
            case TURN_INTERRUPTED: terminal(deviceId,payload,"INTERRUPTED",null,now); break;
            case SKILL_INSTALL_RESULT: skillResult(deviceId,envelope,payload,true,now); break;
            case SKILL_REMOVE_RESULT: skillResult(deviceId,envelope,payload,false,now); break;
            case WORKSPACE_CREATE_RESULT: workspaceCreateResult(deviceId,envelope,payload,now); break;
            case ERROR: agentError(deviceId,envelope,payload,now); break;
            case PONG: break;
            default: throw new IllegalArgumentException("Unsupported Agent event: " + type);
        }
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("type", type.name()); event.put("deviceId", deviceId);
        event.put("correlationId", envelope.getCorrelationId()); event.put("payload", payload);
        Long targetUserId=conversationOwner(deviceId,type,envelope,payload);
        if (targetUserId!=null) sendAfterCommit(targetUserId,event);
        else if (!isConversationScoped(type,payload)) broadcastAfterCommit(event);
        return true;
    }

    @Override @Transactional
    public void disconnected(Long deviceId) {
        deviceMapper.markOffline(deviceId);
        for (com.myharness.codex.entity.po.ConversationTurnPO turn:conversationMapper.selectActiveTurnsForDevice(deviceId)) {
            streams.finish(deviceId,turn.getConversationId(),turn.getId(),"FAILED",null);
        }
        conversationMapper.failActiveTurnsForDevice(deviceId,LocalDateTime.now());
        Map<String,Object> event=new LinkedHashMap<>(); event.put("type","DEVICE_OFFLINE"); event.put("deviceId",deviceId);
        for (Long userId : conversationMapper.selectOwnerIdsForDevice(deviceId)) sendAfterCommit(userId,event);
    }

    private void mergeWorkspaces(Long deviceId, JsonNode workspaces, LocalDateTime now) {
        if (workspaces == null || !workspaces.isArray()) throw new IllegalArgumentException("workspaces must be an array");
        // REGISTER and WORKSPACES_CHANGED both carry complete snapshots. Mark the previous
        // snapshot missing first so stale rows cannot be selected for new conversations.
        deviceMapper.markReportedWorkspacesMissing(deviceId);
        for (JsonNode item : workspaces) {
            AgentWorkspacePO workspace = new AgentWorkspacePO();
            workspace.setDeviceId(deviceId);
            workspace.setWorkspaceName(text(item,"name",128));
            workspace.setRootPath(text(item,"rootPath",1024));
            workspace.setRootPathHash(SecureDigests.sha256(workspace.getRootPath()));
            workspace.setLastReportedAt(now);
            deviceMapper.upsertWorkspace(workspace);
        }
    }

    private void mergeWorkspaceRoots(Long deviceId, JsonNode roots, LocalDateTime now) {
        if (roots == null || roots.isNull()) return;
        if (!roots.isArray()) throw new IllegalArgumentException("workspaceRoots must be an array");
        deviceMapper.disableWorkspaceRoots(deviceId);
        for (JsonNode item : roots) {
            AgentWorkspaceRootPO root = new AgentWorkspaceRootPO();
            root.setDeviceId(deviceId);
            root.setRootName(text(item,"name",128));
            root.setLastReportedAt(now);
            deviceMapper.upsertWorkspaceRoot(root);
        }
    }

    private void workspaceCreateResult(Long deviceId, AgentProtocolEnvelope envelope, JsonNode payload,
                                       LocalDateTime now) {
        Long workspaceId = parseId(envelope.getCorrelationId(), "workspace correlationId");
        String requestId = text(payload, "requestId", 128);
        if (!String.valueOf(workspaceId).equals(requestId)) {
            throw new IllegalArgumentException("workspace requestId does not match correlationId");
        }
        AgentWorkspacePO workspace = new AgentWorkspacePO();
        workspace.setId(workspaceId);
        workspace.setDeviceId(deviceId);
        if (payload.has("success") && payload.get("success").asBoolean()) {
            String rootPath = text(payload, "rootPath", 1024);
            workspace.setRootPath(rootPath);
            workspace.setRootPathHash(SecureDigests.sha256(rootPath));
            workspace.setLastReportedAt(now);
            if (deviceMapper.completeWorkspace(workspace) != 1) {
                throw new IllegalArgumentException("Unknown workspace creation request");
            }
        } else {
            workspace.setFailureCode(optionalText(payload, "errorCode", 64));
            workspace.setFailureMessage(optionalText(payload, "errorMessage", 2000));
            if (deviceMapper.failWorkspace(workspace) != 1) {
                throw new IllegalArgumentException("Unknown workspace creation request");
            }
        }
    }

    private void threadStarted(Long deviceId, JsonNode payload, LocalDateTime now) {
        Long conversationId=id(payload,"conversationId");
        if (payload.hasNonNull("previousCodexThreadId")) {
            if(payload.hasNonNull("expertRuntimeKey")) {
                String key=text(payload,"expertRuntimeKey",64);
                if(!key.matches("[0-9a-f]{64}") || conversationMapper.replaceExpertThread(conversationId,deviceId,id(payload,"turnId"),
                        text(payload,"previousCodexThreadId",128),text(payload,"codexThreadId",128),key,now)!=1)
                    throw new IllegalArgumentException("Expert runtime replacement does not match the pending Turn and previous binding");
                return;
            }
            if (conversationMapper.replaceUnstartedThread(conversationId,deviceId,id(payload,"turnId"),
                    text(payload,"previousCodexThreadId",128),text(payload,"codexThreadId",128),now)!=1)
                throw new IllegalArgumentException("Conversation history or binding does not allow thread reinitialization");
            return;
        }
        conversationMapper.setThreadStarted(conversationId,deviceId,text(payload,"codexThreadId",128),now);
    }
    private void expertRuntimeUpdated(Long deviceId, JsonNode payload, LocalDateTime now) {
        String next=text(payload,"expertRuntimeKey",64);
        String previous=optionalText(payload,"previousExpertRuntimeKey",64);
        if(!next.matches("[0-9a-f]{64}") || (previous!=null && !previous.matches("[0-9a-f]{64}")) ||
                conversationMapper.updateCompatibleExpertRuntime(id(payload,"conversationId"),deviceId,id(payload,"turnId"),
                        text(payload,"codexThreadId",128),previous,next,now)!=1)
            throw new IllegalArgumentException("Compatible expert runtime update does not match the pending Turn and thread binding");
    }
    private void turnStarted(Long deviceId, JsonNode payload, LocalDateTime now) {
        conversationMapper.setTurnStarted(id(payload,"turnId"),id(payload,"conversationId"),deviceId,
                text(payload,"codexTurnId",128),now);
    }
    private void approvalRequired(Long deviceId, JsonNode payload) {
        ApprovalRequestPO approval=new ApprovalRequestPO();
        approval.setConversationId(id(payload,"conversationId")); approval.setTurnId(id(payload,"turnId"));
        approval.setDeviceId(deviceId); approval.setRemoteRequestId(text(payload,"requestId",128));
        approval.setApprovalType(text(payload,"approvalType",32));
        approval.setPayload(payload.has("details") ? payload.get("details").toString() : "{}");
        if (approvalMapper.insert(approval)==1) conversationMapper.waitApproval(approval.getTurnId(),approval.getConversationId());
    }
    private void approvalResolved(Long deviceId, JsonNode payload) {
        ApprovalRequestPO approval=approvalMapper.selectRemote(deviceId,text(payload,"requestId",128));
        if (approval!=null) conversationMapper.resumeTurn(approval.getTurnId(),approval.getConversationId());
    }
    private void terminal(Long deviceId, JsonNode payload, String status, String failureCode, LocalDateTime now) {
        streams.finish(deviceId,id(payload,"conversationId"),id(payload,"turnId"),status,
                payload.hasNonNull("lastEventSeq") ? payload.get("lastEventSeq").asLong() : null);
        conversationMapper.finishTurn(id(payload,"turnId"),id(payload,"conversationId"),deviceId,status,failureCode,
                optionalText(payload,"reason",2000),now);
    }
    private void skillResult(Long deviceId, AgentProtocolEnvelope envelope, JsonNode payload, boolean install, LocalDateTime now) {
        Long deploymentId=parseId(envelope.getCorrelationId(),"device skill correlationId");
        boolean success=payload.has("success") && payload.get("success").asBoolean();
        String status=install ? (success ? "INSTALLED" : "FAILED") : (success ? "REMOVED" : "FAILED");
        skillMapper.updateDeployment(deploymentId,deviceId,status,optionalText(payload,"error",2000),now);
    }
    private void agentError(Long deviceId, AgentProtocolEnvelope envelope, JsonNode payload, LocalDateTime now) {
        String command=optionalText(payload,"commandType",32), code=optionalText(payload,"errorCode",64);
        if ("START_THREAD".equals(command)) conversationMapper.failConversation(parseId(envelope.getCorrelationId(),"conversation correlationId"),deviceId,now);
        else if ("START_TURN".equals(command)) {
            Long turnId=parseId(envelope.getCorrelationId(),"turn correlationId");
            com.myharness.codex.entity.po.ConversationTurnPO turn=conversationMapper.selectTurn(turnId);
            if (turn!=null) {
                streams.finish(deviceId,turn.getConversationId(),turnId,"FAILED",null);
                conversationMapper.finishTurn(turnId,turn.getConversationId(),deviceId,"FAILED",code,
                    optionalText(payload,"message",2000),now);
            }
        } else if ("INSTALL_SKILL".equals(command) || "REMOVE_SKILL".equals(command)) {
            skillMapper.updateDeployment(parseId(envelope.getCorrelationId(),"device skill correlationId"),deviceId,
                    "FAILED",optionalText(payload,"message",2000),now);
        } else if ("CREATE_WORKSPACE".equals(command)) {
            AgentWorkspacePO workspace = new AgentWorkspacePO();
            workspace.setId(parseId(envelope.getCorrelationId(), "workspace correlationId"));
            workspace.setDeviceId(deviceId);
            workspace.setFailureCode(code == null ? "COMMAND_FAILED" : code);
            workspace.setFailureMessage(optionalText(payload, "message", 2000));
            deviceMapper.failWorkspace(workspace);
        }
    }

    private Long id(JsonNode node,String field) { return parseId(text(node,field,32),field); }
    private Long parseId(String value,String field) {
        try { return Long.valueOf(value); } catch (RuntimeException exception) { throw new IllegalArgumentException(field+" must be a numeric id"); }
    }
    private String text(JsonNode node,String field,int max) {
        String value=optionalText(node,field,max);
        if (value==null) throw new IllegalArgumentException(field+" must not be blank");
        return value;
    }
    private String optionalText(JsonNode node,String field,int max) {
        if (node==null || !node.has(field) || node.get(field).isNull()) return null;
        String value=node.get(field).asText().trim();
        if (value.isEmpty()) return null;
        if (value.length()>max) return value.substring(0,max);
        return value;
    }
    private void broadcastAfterCommit(final Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { clientEvents.broadcast(event); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { clientEvents.broadcast(event); }
        });
    }

    private void sendAfterCommit(final Long userId,final Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { clientEvents.sendToUser(userId,event); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { clientEvents.sendToUser(userId,event); }
        });
    }

    private Long conversationOwner(Long deviceId,AgentEventType type,AgentProtocolEnvelope envelope,JsonNode payload) {
        Long conversationId=null;
        switch (type) {
            case THREAD_STARTED:
            case EXPERT_RUNTIME_UPDATED:
            case TURN_STARTED:
            case TURN_EVENT:
            case APPROVAL_REQUIRED:
            case TURN_COMPLETED:
            case TURN_FAILED:
            case TURN_INTERRUPTED:
                conversationId=id(payload,"conversationId");
                break;
            case APPROVAL_RESOLVED:
                ApprovalRequestPO approval=approvalMapper.selectRemote(deviceId,text(payload,"requestId",128));
                if (approval!=null) conversationId=approval.getConversationId();
                break;
            case ERROR:
                String command=optionalText(payload,"commandType",32);
                if ("START_THREAD".equals(command)) conversationId=parseId(envelope.getCorrelationId(),"conversation correlationId");
                else if ("START_TURN".equals(command)) {
                    com.myharness.codex.entity.po.ConversationTurnPO turn=conversationMapper.selectTurn(parseId(envelope.getCorrelationId(),"turn correlationId"));
                    if (turn!=null) conversationId=turn.getConversationId();
                }
                break;
            default:
                break;
        }
        if (conversationId==null) return null;
        ConversationPO conversation=conversationMapper.selectConversation(conversationId);
        return conversation==null ? null : conversation.getUserId();
    }

    private boolean isConversationScoped(AgentEventType type,JsonNode payload) {
        switch (type) {
            case THREAD_STARTED:
            case EXPERT_RUNTIME_UPDATED:
            case TURN_STARTED:
            case TURN_EVENT:
            case APPROVAL_REQUIRED:
            case APPROVAL_RESOLVED:
            case TURN_COMPLETED:
            case TURN_FAILED:
            case TURN_INTERRUPTED:
                return true;
            case ERROR:
                String command=optionalText(payload,"commandType",32);
                return "START_THREAD".equals(command) || "START_TURN".equals(command);
            default:
                return false;
        }
    }
}
