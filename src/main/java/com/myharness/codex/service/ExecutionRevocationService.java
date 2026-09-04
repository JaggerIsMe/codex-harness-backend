package com.myharness.codex.service;
import com.myharness.codex.entity.vo.RevokedTurnVO;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
/** Re-evaluates running work, including requests started just before an authorization change. */
@Service
public class ExecutionRevocationService {
    private final ConversationMapper conversations;private final ApprovalMapper approvals;
    private final AuthorizationService access;private final AgentCommandGateway gateway;
    public ExecutionRevocationService(ConversationMapper conversations,ApprovalMapper approvals,AuthorizationService access,AgentCommandGateway gateway) {
        this.conversations=conversations;this.approvals=approvals;this.access=access;this.gateway=gateway;
    }
    @Scheduled(fixedDelay=5000) public void interruptRevokedWork() {
        for(RevokedTurnVO turn:conversations.selectRunningWork()) {
            if(access.canUseDevice(turn.userId(),turn.deviceId())) continue;
            if(!gateway.isOnline(turn.deviceCode())) continue;
            try {
                for(var approval:approvals.selectByConversation(turn.conversationId())) {
                    if("PENDING".equals(approval.getStatus())) {
                        gateway.send(turn.deviceCode(),new AgentCommand("RESOLVE_APPROVAL",String.valueOf(approval.getId()),
                                Map.of("requestId",approval.getRemoteRequestId(),"decision","CANCEL")));
                        approvals.decide(approval.getId(),"CANCELLED",null,LocalDateTime.now(ZoneOffset.UTC));
                    }
                }
                gateway.send(turn.deviceCode(),new AgentCommand("INTERRUPT_TURN",String.valueOf(turn.turnId()),
                        Map.of("conversationId",String.valueOf(turn.conversationId()),"turnId",String.valueOf(turn.turnId()))));
            } catch(RuntimeException ex) {
                // Retry while the Turn is active. The Agent terminal event remains authoritative.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Unable to interrupt revoked Turn {}",turn.turnId());
            }
        }
    }
}

