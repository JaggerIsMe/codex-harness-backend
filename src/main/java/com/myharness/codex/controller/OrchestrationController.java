package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.CreateOrchestrationDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.OrchestrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/orchestrations")
public class OrchestrationController {
    private final OrchestrationService service;
    public OrchestrationController(OrchestrationService service){this.service=service;}
    @GetMapping("/availability") public ApiResponseVO<Boolean> available(){UserContext.requireCurrentUser();return ApiResponseVO.success(service.enabled());}
    @GetMapping public ApiResponseVO<List<OrchestrationVO>> list(@PathVariable Long projectId,@RequestParam(defaultValue="") String keyword) {
        return ApiResponseVO.success(service.list(projectId,UserContext.requireCurrentUser().getId(),keyword));
    }
    @PostMapping public ApiResponseVO<OrchestrationVO> create(@PathVariable Long projectId,@Valid @RequestBody CreateOrchestrationDTO dto) {
        return ApiResponseVO.success(service.create(projectId,dto,UserContext.requireCurrentUser().getId()));
    }
    @GetMapping("/{id}") public ApiResponseVO<OrchestrationVO> get(@PathVariable Long projectId,@PathVariable Long id) {
        return ApiResponseVO.success(service.get(projectId,id,UserContext.requireCurrentUser().getId()));
    }
    @PostMapping("/{id}/cancel") public ApiResponseVO<OrchestrationVO> cancel(@PathVariable Long projectId,@PathVariable Long id) {
        return ApiResponseVO.success(service.cancel(projectId,id,UserContext.requireCurrentUser().getId()));
    }
    @PostMapping("/{id}/steps/{stepId}/continue") public ApiResponseVO<OrchestrationVO> continueStep(
        @PathVariable Long projectId,@PathVariable Long id,@PathVariable Long stepId,
        @Valid @RequestBody com.myharness.codex.entity.dto.ContinueOrchestrationStepDTO input) {
        return ApiResponseVO.success(service.continueStep(projectId,id,stepId,input,UserContext.requireCurrentUser().getId()));
    }
    @PostMapping("/{id}/steps/{stepId}/recheck") public ApiResponseVO<OrchestrationVO> recheckStep(
        @PathVariable Long projectId,@PathVariable Long id,@PathVariable Long stepId,
        @Valid @RequestBody com.myharness.codex.entity.dto.RecheckOrchestrationStepDTO input) {
        return ApiResponseVO.success(service.recheckStep(projectId,id,stepId,input,UserContext.requireCurrentUser().getId()));
    }
    @PostMapping("/{id}/acknowledge-stopped") public ApiResponseVO<OrchestrationVO> acknowledgeStopped(@PathVariable Long projectId,@PathVariable Long id,
        @Valid @RequestBody com.myharness.codex.entity.dto.AcknowledgeOrchestrationStopDTO input) {
        return ApiResponseVO.success(service.acknowledgeStopped(projectId,id,UserContext.requireCurrentUser().getId(),input.confirmedStopped()));
    }
}
