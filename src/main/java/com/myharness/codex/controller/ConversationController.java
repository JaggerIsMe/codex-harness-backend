package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.CreateConversationDTO;
import com.myharness.codex.entity.dto.StartTurnDTO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.ConversationVO;
import com.myharness.codex.entity.vo.TurnVO;
import com.myharness.codex.entity.vo.ConversationMessageVO;
import com.myharness.codex.entity.vo.ApprovalVO;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ConversationService;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import com.myharness.codex.entity.vo.MessageStateVO;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/conversations")
public class ConversationController {
    private final ConversationService service;
    public ConversationController(ConversationService service) { this.service=service; }
    @PostMapping public ApiResponseVO<ConversationVO> create(@PathVariable Long projectId,@Valid @RequestBody CreateConversationDTO dto) {
        return ApiResponseVO.success(service.createConversation(projectId,dto,UserContext.requireCurrentUser().getId()));
    }
    @GetMapping public ApiResponseVO<List<ConversationVO>> list(@PathVariable Long projectId) {
        return ApiResponseVO.success(service.getProjectConversations(projectId,UserContext.requireCurrentUser().getId()));
    }
    @GetMapping("/{id}") public ApiResponseVO<ConversationVO> get(@PathVariable Long projectId,@PathVariable Long id) {
        return ApiResponseVO.success(service.getConversation(projectId,id,UserContext.requireCurrentUser().getId()));
    }
    @GetMapping("/{id}/active-turn") public ApiResponseVO<TurnVO> activeTurn(@PathVariable Long projectId,@PathVariable Long id) {
        return ApiResponseVO.success(service.getActiveTurn(projectId,id,UserContext.requireCurrentUser().getId()));
    }
    @GetMapping("/{id}/messages") public ApiResponseVO<List<ConversationMessageVO>> messages(@PathVariable Long projectId,@PathVariable Long id) {
        return ApiResponseVO.success(service.getMessages(projectId,id,UserContext.requireCurrentUser().getId()));
    }
    @GetMapping("/{id}/message-state") public ApiResponseVO<MessageStateVO> messageState(@PathVariable Long projectId,@PathVariable Long id,
            @RequestParam(defaultValue="0") long before,@RequestParam(defaultValue="200") int limit,
            @RequestParam(required=false) Long turnId,@RequestParam(defaultValue="-1") long after) {
        return ApiResponseVO.success(service.getMessageState(projectId,id,UserContext.requireCurrentUser().getId(),before,limit,turnId,after));
    }
    @GetMapping("/{id}/approvals") public ApiResponseVO<List<ApprovalVO>> approvals(@PathVariable Long projectId,@PathVariable Long id) {
        return ApiResponseVO.success(service.getApprovals(projectId,id,UserContext.requireCurrentUser().getId()));
    }
    @PostMapping("/{id}/turns") public ApiResponseVO<TurnVO> start(@PathVariable Long projectId,@PathVariable Long id,@Valid @RequestBody StartTurnDTO dto) {
        return ApiResponseVO.success(service.startTurn(projectId,id,dto,UserContext.requireCurrentUser().getId()));
    }
    @PostMapping("/{conversationId}/turns/{turnId}/interrupt") public ApiResponseVO<Void> interrupt(@PathVariable Long projectId,@PathVariable Long conversationId,@PathVariable Long turnId) {
        service.interruptTurn(projectId,conversationId,turnId,UserContext.requireCurrentUser().getId()); return ApiResponseVO.success(null);
    }
}
