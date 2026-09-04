package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.ApprovalDecisionDTO;
import com.myharness.codex.entity.po.ApprovalRequestPO;
import com.myharness.codex.entity.vo.ApprovalVO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalService service;
    private final ObjectMapper objectMapper;
    public ApprovalController(ApprovalService service,ObjectMapper objectMapper) { this.service=service; this.objectMapper=objectMapper; }
    @PostMapping("/{id}/decision")
    public ApiResponseVO<ApprovalVO> decide(@PathVariable Long id,@Valid @RequestBody ApprovalDecisionDTO dto) {
        return ApiResponseVO.success(new ApprovalVO(service.decide(id,dto,UserContext.requireCurrentUser().getId()),objectMapper));
    }
}
