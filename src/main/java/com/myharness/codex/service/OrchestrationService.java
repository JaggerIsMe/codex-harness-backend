package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.OrchestrationProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.*;
import com.myharness.codex.websocket.ClientEventWebSocketHandler;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Single-backend durable orchestration. No database transaction spans a device command. */
@Service
public class OrchestrationService {
    private static final Set<String> TERMINAL=Set.of("SUCCEEDED","FAILED","CANCELLED");
    private static final Set<String> STEP_TERMINAL=Set.of("SUCCEEDED","FAILED","CANCELLED","SKIPPED");
    private static final Set<String> TURN_ACTIVE=Set.of("CREATED","RUNNING","WAITING_APPROVAL");
    private static final Set<String> PAUSED=Set.of("WAITING_USER","VALIDATION_FAILED");
    private final OrchestrationMapper mapper;
    private final ProjectMapper projects;
    private final ConversationMapper turns;
    private final ConversationService conversations;
    private final ExpertService experts;
    private final AuthorizationService access;
    private final AgentCommandGateway gateway;
    private final OrchestrationProperties properties;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final ClientEventWebSocketHandler events;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(OrchestrationService.class);

    public OrchestrationService(OrchestrationMapper mapper,ProjectMapper projects,ConversationMapper turns,
        ConversationService conversations,ExpertService experts,AuthorizationService access,AgentCommandGateway gateway,
        OrchestrationProperties properties,TransactionTemplate transactions,ObjectMapper json,ClientEventWebSocketHandler events) {
        this.mapper=mapper;this.projects=projects;this.turns=turns;this.conversations=conversations;this.experts=experts;
        this.access=access;this.gateway=gateway;this.properties=properties;this.transactions=transactions;this.json=json;this.events=events;
    }
    public boolean enabled(){return properties.isEnabled();}
    private void requireEnabled(){if(!enabled())throw new BusinessException(ErrorCode.CONFLICT,"多 Expert 编排尚未启用，请完成数据库迁移和 Agent 升级后启用");}
    private ProjectPO owned(Long projectId,Long userId) {
        access.requirePermission(userId,"conversation:read");
        ProjectPO p=projects.selectOwned(projectId,userId);
        if(p==null)throw new BusinessException(ErrorCode.NOT_FOUND,"项目不存在");
        access.requireDevice(userId,p.getDeviceId());
        if(!"ACTIVE".equals(p.getStatus()) || !"ENABLED".equals(p.getWorkspaceStatus()))
            throw new BusinessException(ErrorCode.CONFLICT,"项目执行目录尚未就绪");
        return p;
    }
    public synchronized OrchestrationVO create(Long projectId,CreateOrchestrationDTO dto,Long userId) {
        requireEnabled();ProjectPO p=owned(projectId,userId);
        access.requirePermission(userId,"conversation:create");access.requirePermission(userId,"turn:start");
        String hash=SecureDigests.sha256(write(List.of(projectId,dto)));
        var previous=mapper.byRequest(userId,dto.requestKey());
        if(previous!=null) {
            if(!hash.equals(previous.getRequestHash()))throw new BusinessException(ErrorCode.CONFLICT,"创建标识已用于不同内容");
            return view(previous);
        }
        WorkflowGraph graph;
        try {graph=new WorkflowGraph(dto.workflow(),json);}
        catch(IllegalArgumentException failure){throw new BusinessException(ErrorCode.INVALID_REQUEST,failure.getMessage());}
        var available=experts.projectExperts(projectId,userId).experts().stream().filter(ProjectExpertVO::available).map(ProjectExpertVO::expertId).toList();
        if(!available.containsAll(graph.definition().nodes().stream().filter(n->"EXPERT".equals(n.kind())).map(WorkflowDTO.Node::expertId).toList()))
            throw new BusinessException(ErrorCode.CONFLICT,"存在未授权或不可用的项目专家");
        var execution=transactions.execute(tx -> {
            experts.lockProject(projectId);owned(projectId,userId);
            if(mapper.pendingForUser(userId)>=properties.getMaxQueuedPerUser())throw new BusinessException(ErrorCode.CONFLICT,"待处理编排已达到上限");
            var e=new OrchestrationExecutionPO();e.setProjectId(projectId);e.setUserId(userId);e.setDeviceId(p.getDeviceId());
            e.setTitle(dto.title().trim());e.setGoal(dto.goal());e.setRequestKey(dto.requestKey());e.setRequestHash(hash);
            e.setPlanJson(write(graph.definition()));
            mapper.insert(e);
            for(int i=0;i<graph.definition().nodes().size();i++) {
                var definition=graph.definition().nodes().get(i);var step=new OrchestrationStepPO();
                step.setExecutionId(e.getId());step.setPosition(i);step.setName(definition.name());step.setObjective(definition.objective()==null?"":definition.objective());step.setExpertId(definition.expertId());
                mapper.insertStep(step);
            }
            return mapper.get(e.getId());
        });
        notifyChanged(execution);return view(execution);
    }
    public List<OrchestrationVO> list(Long projectId,Long userId,String keyword) {
        owned(projectId,userId);if(!enabled())return List.of();
        String q=keyword==null?"":keyword.trim();if(q.length()>120)throw new BusinessException(ErrorCode.INVALID_REQUEST,"搜索关键词过长");
        return mapper.list(projectId,userId,q).stream().map(p->new OrchestrationVO(p,List.of())).toList();
    }
    public OrchestrationVO get(Long projectId,Long id,Long userId) {
        requireEnabled();owned(projectId,userId);return view(requireOwned(projectId,id,userId));
    }
    private OrchestrationExecutionPO requireOwned(Long projectId,Long id,Long userId) {
        var e=mapper.get(id);
        if(e==null || !projectId.equals(e.getProjectId()) || !userId.equals(e.getUserId()))throw new BusinessException(ErrorCode.NOT_FOUND,"编排不存在");
        return e;
    }
    public synchronized OrchestrationVO cancel(Long projectId,Long id,Long userId) {
        requireEnabled();owned(projectId,userId);access.requirePermission(userId,"turn:interrupt");
        var e=requireOwned(projectId,id,userId);
        if(!TERMINAL.contains(e.getStatus())) {
            mapper.status(id,"CANCELING","用户请求停止编排，等待设备确认");advance(mapper.get(id));notifyChanged(mapper.get(id));
        }
        return view(mapper.get(id));
    }
    public synchronized OrchestrationVO continueStep(Long projectId,Long id,Long stepId,ContinueOrchestrationStepDTO input,Long userId) {
        requireEnabled();var project=owned(projectId,userId);access.requirePermission(userId,"turn:start");
        var execution=requireOwned(projectId,id,userId);var step=mapper.step(stepId);
        if(step==null || !id.equals(step.getExecutionId()) || step.getConversationId()==null)
            throw new BusinessException(ErrorCode.NOT_FOUND,"节点不存在");
        var dto=new StartTurnDTO();dto.setMessage(input.message());
        dto.setClientRequestId(SecureDigests.sha256("node-"+stepId+"-"+input.expectedTurnId()+"-"+input.requestKey()));
        var previous=turns.byClientRequest(step.getConversationId(),dto.getClientRequestId());
        if(previous!=null) {
            if(mapper.hasAttempt(stepId,previous.getId())!=1 || !SecureDigests.sha256(write(dto)).equals(previous.getRequestHash()))
                throw new BusinessException(ErrorCode.CONFLICT,"发送标识已用于不同内容");
            return view(execution);
        }
        transactions.executeWithoutResult(tx->{
            experts.lockProject(projectId);owned(projectId,userId);
            var fresh=mapper.get(id);var selected=mapper.step(stepId);
            if(TERMINAL.contains(fresh.getStatus()) || fresh.isCancelRequested() || !"RUNNING".equals(fresh.getStatus())
                || !PAUSED.contains(selected.getStatus()) || !input.expectedTurnId().equals(selected.getTurnId())
                || !"COMPLETED".equals(selected.getTerminalStatus()))
                throw new BusinessException(ErrorCode.CONFLICT,"仅当前暂停节点可以补充信息；请刷新执行状态");
            var current=new WorkflowGraph(readWorkflow(fresh),json).current(mapper.steps(id));
            if(current==null || !stepId.equals(current.getId()) || mapper.activeProjectTurns(projectId)>0 || mapper.pendingApprovals(selected.getTurnId())>0)
                throw new BusinessException(ErrorCode.CONFLICT,"节点仍在执行或有待处理审批，请先处理后再继续");
            if(!gateway.isOnline(project.getDeviceCode()) || mapper.activeDeviceTurns(project.getDeviceId())>=properties.getMaxActiveTurnsPerDevice())
                throw new BusinessException(ErrorCode.CONFLICT,"设备离线或并发额度已满，请稍后重试");
            if(mapper.claim(stepId,selected.getStatus(),"DISPATCHING",null)!=1)
                throw new BusinessException(ErrorCode.CONFLICT,"节点状态已变化，请刷新");
        });
        try {conversations.startOrchestrationTurn(projectId,step.getConversationId(),dto,userId,stepId);}
        catch(RuntimeException failure) {
            attention(execution,mapper.step(stepId),"续聊派发未确认，未自动重发；请核实步骤会话与设备");throw failure;
        }
        notifyChanged(mapper.get(id));return view(mapper.get(id));
    }
    public synchronized OrchestrationVO recheckStep(Long projectId,Long id,Long stepId,RecheckOrchestrationStepDTO input,Long userId) {
        requireEnabled();owned(projectId,userId);access.requirePermission(userId,"turn:start");requireOwned(projectId,id,userId);
        transactions.executeWithoutResult(tx->{
            experts.lockProject(projectId);owned(projectId,userId);
            var e=requireOwned(projectId,id,userId);var selected=mapper.step(stepId);
            if(selected==null || !id.equals(selected.getExecutionId()))throw new BusinessException(ErrorCode.NOT_FOUND,"节点不存在");
            if(!input.expectedTurnId().equals(selected.getTurnId()))throw new BusinessException(ErrorCode.CONFLICT,"节点轮次已变化，请刷新");
            if("SUCCEEDED".equals(selected.getStatus()))return;
            if(e.isCancelRequested() || !Set.of("RUNNING","NEEDS_ATTENTION").contains(e.getStatus())
                || !Set.of("WAITING_USER","VALIDATION_FAILED","NEEDS_ATTENTION").contains(selected.getStatus()))
                throw new BusinessException(ErrorCode.CONFLICT,"仅当前暂停或待核实节点可重新校验");
            var current=new WorkflowGraph(readWorkflow(e),json).current(mapper.steps(id));
            var snapshot=mapper.observation(stepId);
            if(current==null || !stepId.equals(current.getId()) || snapshot==null || !input.expectedTurnId().equals(snapshot.turnId())
                || !"COMPLETED".equals(snapshot.turnStatus()) || !"COMPLETED".equals(snapshot.terminalStatus())
                || mapper.activeProjectTurns(projectId)>0 || mapper.pendingApprovals(snapshot.turnId())>0)
                throw new BusinessException(ErrorCode.CONFLICT,"节点仍在执行、缺少终态或存在未处理审批，不能重新校验");
            try {
                var receipt=WorkflowOutcomeRecovery.recover(json,WorkflowCompletionGate.read(json,snapshot.checkpointJson()),
                    snapshot.turnId(),mapper.outcomeActivities(snapshot.turnId()));
                receipt.put("recheckedBy",userId).put("recheckedAt",LocalDateTime.now().toString());
                mapper.checkpoint(snapshot.turnId(),write(receipt));mapper.attemptCheckpoint(snapshot.turnId(),write(receipt));
            } catch(IllegalArgumentException failure){throw new BusinessException(ErrorCode.CONFLICT,failure.getMessage());}
            mapper.stepStatus(stepId,"VALIDATING",null);mapper.status(id,"RUNNING",null);
            observe(mapper.get(id),mapper.step(stepId),false);
        });
        notifyChanged(mapper.get(id));return view(mapper.get(id));
    }
    @Scheduled(fixedDelayString="${harness.orchestration.scan-interval-ms:2000}",initialDelay=5000)
    public synchronized void tick() {
        if(!enabled())return;
        for(var e:mapper.scheduled()) {
            try {advance(e);}
            catch(Exception failure) {LOG.warn("Cannot reconcile orchestration {}",e.getId(),failure);}
            finally {mapper.touch(e.getId());}
        }
    }
    public synchronized OrchestrationVO acknowledgeStopped(Long projectId,Long id,Long userId,boolean confirmed) {
        requireEnabled();owned(projectId,userId);access.requirePermission(userId,"turn:interrupt");
        var e=requireOwned(projectId,id,userId);
        if(!confirmed || !"NEEDS_ATTENTION".equals(e.getStatus()))
            throw new BusinessException(ErrorCode.CONFLICT,"仅待核实编排可在人工核实停止后结束记录");
        transactions.executeWithoutResult(tx -> {
            experts.lockProject(projectId);
            if(mapper.activeProjectTurns(projectId)>0)
                throw new BusinessException(ErrorCode.CONFLICT,"项目仍有活动 Turn，请先请求停止并等待结束");
            for(var s:mapper.steps(id))if(!STEP_TERMINAL.contains(s.getStatus()))
                mapper.stepStatus(s.getId(),"FAILED","用户已人工核实设备执行停止；未重试");
            mapper.status(id,"FAILED","用户 "+userId+" 已人工核实执行停止并结束记录；未回滚或重试");
        });
        notifyChanged(mapper.get(id));return view(mapper.get(id));
    }
    private void advance(OrchestrationExecutionPO e) {
        if(TERMINAL.contains(e.getStatus()))return;
        var steps=mapper.steps(e.getId());
        if(steps.stream().anyMatch(s->"FAILED".equals(s.getStatus()))) {
            finish(e,"FAILED","步骤失败，后续步骤未执行");return;
        }
        if(steps.stream().anyMatch(s->"CANCELLED".equals(s.getStatus()))) {
            finish(e,"CANCELLED","编排已停止");return;
        }
        boolean cancel=e.isCancelRequested() || "CANCELING".equals(e.getStatus());
        boolean paused=steps.stream().anyMatch(s->PAUSED.contains(s.getStatus()));
        var currentTurn=steps.stream().filter(s->s.getTurnId()!=null && !STEP_TERMINAL.contains(s.getStatus()))
            .map(s->turns.selectTurn(s.getTurnId())).filter(Objects::nonNull).findFirst().orElse(null);
        var lastProgress=steps.stream().map(OrchestrationStepPO::getUpdatedAt).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(e.getCreatedAt());
        var timeoutFrom=currentTurn!=null && currentTurn.getCreatedAt()!=null?currentTurn.getCreatedAt():lastProgress;
        if(!cancel && !paused && timeoutFrom.plusMinutes(properties.getExecutionTimeoutMinutes()).isBefore(LocalDateTime.now())) {
            change(e,"CANCELING","编排已超过时限，等待设备停止");cancel=true;
        }
        // Existing active device work is reconciled even for a retired/invalid plan.
        var active=steps.stream().filter(s->!STEP_TERMINAL.contains(s.getStatus()) && s.getTurnId()!=null).findFirst().orElse(null);
        if(active!=null){observe(e,active,cancel);return;}
        if(cancel) {
            var uncertain=steps.stream().filter(s->"DISPATCHING".equals(s.getStatus())).findFirst().orElse(null);
            if(uncertain!=null){attention(e,uncertain,"派发结果不确定，请核实设备执行状态");return;}
            finish(e,"CANCELLED","编排已停止");return;
        }
        WorkflowGraph graph;
        try {graph=new WorkflowGraph(readWorkflow(e),json);}
        catch(IllegalArgumentException failure){change(e,"NEEDS_ATTENTION","旧版或无效工作流已停止后续派发；请停止记录并在画布重新创建");return;}
        OrchestrationStepPO step;
        try {step=graph.current(steps);skipUnselected(graph,steps);}
        catch(IllegalArgumentException failure){change(e,"NEEDS_ATTENTION",failure.getMessage());return;}
        if(step==null){skipUnselected(graph,steps);change(e,"SUCCEEDED",null);return;}
        // A durable claim without its atomic local record means the process exited during preparation.
        if(Set.of("CREATING","DISPATCHING").contains(step.getStatus())) {
            attention(e,step,"执行准备未完成，未自动重发；请检查对应会话和设备");return;
        }
        ProjectPO p;
        try {
            p=owned(e.getProjectId(),e.getUserId());access.requirePermission(e.getUserId(),"turn:start");
        } catch(BusinessException failure){finish(e,"FAILED","授权或项目状态已变化，停止后续步骤");return;}
        if("NEEDS_ATTENTION".equals(e.getStatus()))return;
        if(!gateway.isOnline(p.getDeviceCode()))return;
        if(mapper.activeDeviceTurns(e.getDeviceId())>=properties.getMaxActiveTurnsPerDevice() || mapper.activeProjectTurns(e.getProjectId())>0)return;
        if("QUEUED".equals(e.getStatus())) {
            boolean claimed=Boolean.TRUE.equals(transactions.execute(tx->{
                experts.lockProject(e.getProjectId());
                if(mapper.otherActive(e.getProjectId(),e.getId())>0)return false;
                mapper.status(e.getId(),"RUNNING",null);return true;
            }));
            if(!claimed)return;e.setStatus("RUNNING");notifyChanged(e);
        }
        try {
            if("PENDING".equals(step.getStatus())) {
                if("START".equals(graph.node(step).kind())) {
                    mapper.result(step.getId(),write(new OrchestrationStepResultVO(1,"流程开始",List.of(),null,null,false)));
                    notifyChanged(e);return;
                }
                if("END".equals(graph.node(step).kind())) {
                    mapper.result(step.getId(),write(new OrchestrationStepResultVO(1,"流程结束",List.of(),null,null,false)));
                    skipUnselected(graph,mapper.steps(e.getId()));change(e,"SUCCEEDED",null);return;
                }
                if("BRANCH".equals(graph.node(step).kind())) {
                    boolean choice=graph.evaluate(graph.node(step).condition(),steps);
                    mapper.result(step.getId(),write(new OrchestrationStepResultVO(1,Boolean.toString(choice),List.of(),null,null,false)));
                    skipUnselected(graph,mapper.steps(e.getId()));notifyChanged(e);return;
                }
                if(mapper.claim(step.getId(),"PENDING","CREATING",null)!=1)return;
                var input=new CreateConversationDTO();input.setExpertId(step.getExpertId());input.setTitle(e.getTitle()+" · "+step.getName());
                conversations.createOrchestrationConversation(e.getProjectId(),input,e.getUserId(),step.getId());
                notifyChanged(e);return;
            }
            if("WAITING_THREAD".equals(step.getStatus())) {
                var conversation=turns.selectConversation(step.getConversationId());
                if(conversation==null || !"ACTIVE".equals(conversation.getStatus())){finish(e,"FAILED","步骤会话初始化失败");return;}
                if(conversation.getCodexThreadId()==null)return;
                String input=graph.message(graph.node(step),e.getGoal(),steps);
                if(mapper.claim(step.getId(),"WAITING_THREAD","DISPATCHING",input)!=1)return;
                var dto=new StartTurnDTO();dto.setMessage(input);dto.setClientRequestId("orchestration-"+step.getId()+"-1");
                conversations.startOrchestrationTurn(e.getProjectId(),step.getConversationId(),dto,e.getUserId(),step.getId());
                notifyChanged(e);
            }
        } catch(RuntimeException failure) {
            var fresh=mapper.step(step.getId());
            if(fresh.getTurnId()!=null)attention(e,fresh,"Turn 派发未确认，请核实设备；不会自动重试");
            else finish(e,"FAILED",failure instanceof BusinessException || failure instanceof IllegalArgumentException ? failure.getMessage() : "步骤准备失败，请检查会话与服务日志");
        }
    }
    private void observe(OrchestrationExecutionPO e,OrchestrationStepPO step,boolean cancel) {
        var snapshot=mapper.observation(step.getId());
        if(snapshot==null){attention(e,step,"关联 Turn 不存在");return;}
        if(!Objects.equals(step.getTurnId(),snapshot.turnId()))return;
        step.setStatus(snapshot.stepStatus());step.setTerminalStatus(snapshot.terminalStatus());step.setCheckpointJson(snapshot.checkpointJson());
        var turn=new ConversationTurnPO();turn.setId(snapshot.turnId());turn.setConversationId(step.getConversationId());
        turn.setStatus(snapshot.turnStatus());turn.setExpertVersionId(snapshot.expertVersionId());
        turn.setFailureCode(snapshot.failureCode());turn.setFailureMessage(snapshot.failureMessage());
        if(!cancel && PAUSED.contains(step.getStatus()))return;
        if(!cancel && "DISPATCHING".equals(step.getStatus())){attention(e,step,"续聊派发未确认，未自动重发；请核实设备");return;}
        if(step.getTerminalStatus()!=null) {
            if(cancel){mapper.stepStatus(step.getId(),"CANCELLED",null);finish(e,"CANCELLED","设备已确认结束");return;}
            if(!"COMPLETED".equals(step.getTerminalStatus()) || !"COMPLETED".equals(turn.getStatus())) {
                mapper.stepStatus(step.getId(),"FAILED","设备执行未成功完成");finish(e,"FAILED","步骤执行失败或已中断");return;
            }
            var receipt=WorkflowCompletionGate.read(json,step.getCheckpointJson());
            if(mapper.pendingApprovals(turn.getId())>0 || receipt.path("unresolvedApproval").asBoolean(false)) {
                attention(e,step,"本轮结束时仍有未完成的审批或决定。请核实审批记录，不能直接提交节点结果。");return;
            }
            if(receipt.path("protocol").asInt()!=1 || !receipt.path("summary").isTextual() || receipt.path("summary").asText().isBlank()
                || !Set.of("COMPLETE","WAITING_USER").contains(receipt.path("state").asText())) {
                attention(e,step,"缺少有效的节点回执，请核实 Agent 或重新校验；这不表示需要补充业务信息。");return;
            }
            if("WAITING_USER".equals(receipt.path("state").asText())) {
                String question=receipt.path("summary").asText();
                mapper.stepStatus(step.getId(),"WAITING_USER",question.substring(0,Math.min(question.length(),1000)));
                if("RUNNING".equals(e.getStatus()) && e.getFailureMessage()==null)notifyChanged(e);
                else change(e,"RUNNING",null);
                return;
            }
            mapper.stepStatus(step.getId(),"VALIDATING",null);
            try {
                var result=OrchestrationResults.result(turn,mapper.messages(turn.getId()));
                var workflow=readWorkflow(e);
                if(workflow!=null) {
                    var graph=new WorkflowGraph(workflow,json);
                    result=WorkflowCompletionGate.validate(graph.node(step),receipt,result);
                    result=graph.output(graph.node(step),result);
                } else throw new IllegalArgumentException("工作流定义无效，不能确认完成");
                mapper.result(step.getId(),write(result));
                change(e,"RUNNING",null);
                notifyChanged(e);
            } catch(IllegalArgumentException failure) {mapper.stepStatus(step.getId(),"VALIDATION_FAILED",failure.getMessage());notifyChanged(e);}
            return;
        }
        if(cancel) {
            var c=turns.selectConversation(step.getConversationId());
            if(c!=null && gateway.isOnline(c.getDeviceCode())) {
                String commandId=UUID.nameUUIDFromBytes(("orchestration-stop-"+step.getId()).getBytes(StandardCharsets.UTF_8)).toString();
                // Dispatch once per process/device connection via the normal deduplicator; never invent a terminal acknowledgement.
                gateway.send(c.getDeviceCode(),new AgentCommand("INTERRUPT_TURN",String.valueOf(turn.getId()),
                    Map.of("conversationId",String.valueOf(c.getId()),"turnId",String.valueOf(turn.getId())),commandId));
            }
            if(!TURN_ACTIVE.contains(turn.getStatus()))attention(e,step,"等待设备结束回执，当前记录不足以确认执行已停止");
            return;
        }
        if(!TURN_ACTIVE.contains(turn.getStatus())){
            String reason="未收到设备确定终态，停止推进；请核实会话与设备";
            if("FAILED".equals(turn.getStatus()) && turn.getFailureMessage()!=null && !turn.getFailureMessage().isBlank()) {
                reason="Turn 执行失败，未收到设备终态回执，已停止推进；"
                    +(turn.getFailureCode()==null?"":turn.getFailureCode()+"：")+turn.getFailureMessage();
            }
            attention(e,step,reason.substring(0,Math.min(reason.length(),1000)));return;
        }
        String next="WAITING_APPROVAL".equals(turn.getStatus())?"WAITING_APPROVAL":"RUNNING";
        if(!next.equals(step.getStatus())){mapper.stepStatus(step.getId(),next,null);notifyChanged(e);}
    }
    private void skipUnselected(WorkflowGraph graph,List<OrchestrationStepPO> steps) {
        var potential=graph.potentialPositions(steps);
        for(var s:steps)if("PENDING".equals(s.getStatus()) && !potential.contains(s.getPosition()))mapper.stepStatus(s.getId(),"SKIPPED","条件未选择此路径");
    }
    private WorkflowDTO readWorkflow(OrchestrationExecutionPO e) {
        try {var tree=json.readTree(e.getPlanJson());return Set.of(2,3,4).contains(tree.path("schemaVersion").asInt())?json.treeToValue(tree,WorkflowDTO.class):null;}
        catch(Exception failure){return null;}
    }
    private void attention(OrchestrationExecutionPO e,OrchestrationStepPO step,String reason) {
        mapper.stepStatus(step.getId(),"NEEDS_ATTENTION",reason);change(e,"NEEDS_ATTENTION",reason);
    }
    private void finish(OrchestrationExecutionPO e,String status,String reason) {
        for(var s:mapper.steps(e.getId()))if(!STEP_TERMINAL.contains(s.getStatus()))mapper.stepStatus(s.getId(),"CANCELLED".equals(status)?"CANCELLED":"FAILED",reason);
        change(e,status,reason);
    }
    private void change(OrchestrationExecutionPO e,String status,String reason) {
        if(status.equals(e.getStatus()) && Objects.equals(reason,e.getFailureMessage()))return;
        mapper.status(e.getId(),status,reason);e.setStatus(status);e.setFailureMessage(reason);notifyChanged(e);
    }
    private void notifyChanged(OrchestrationExecutionPO e) {
        events.sendToUser(e.getUserId(),new ChangeEvent("ORCHESTRATION_UPDATED",new ChangePayload(e.getProjectId(),e.getId())));
    }
    private OrchestrationVO view(OrchestrationExecutionPO e) {
        return new OrchestrationVO(e,mapper.steps(e.getId()).stream().map(s->new OrchestrationStepVO(s,readResult(s.getResultJson()))).toList(),readWorkflow(e));
    }
    private OrchestrationStepResultVO readResult(String value) {
        if(value==null)return null;
        try{return json.readValue(value,OrchestrationStepResultVO.class);}
        catch(Exception failure){throw new IllegalStateException("Stored orchestration result is invalid",failure);}
    }
    private String write(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception failure){throw new IllegalArgumentException("Invalid orchestration data",failure);}
    }
    public record ChangePayload(Long projectId,Long executionId) {}
    public record ChangeEvent(String type,ChangePayload payload) {}
}
