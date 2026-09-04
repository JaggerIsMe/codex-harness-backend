package com.myharness.codex.controller;

import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.SkillDeploymentVO;
import com.myharness.codex.entity.dto.DeploySkillDTO;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.SkillDeploymentService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/skill-deployments")
public class SkillDeploymentController {
    private final SkillDeploymentService service;
    public SkillDeploymentController(SkillDeploymentService service){this.service=service;}
    @GetMapping
    public ApiResponseVO<List<SkillDeploymentVO>> list(@RequestParam(required=false) String keyword,
                                                        @RequestParam(required=false) String status,
                                                        @RequestParam(required=false) String scopeType){return ApiResponseVO.success(service.list(keyword,status,scopeType,currentUserId()));}
    @PostMapping
    public ApiResponseVO<SkillDeploymentVO> deploy(@Valid @RequestBody DeploySkillDTO dto){
        return ApiResponseVO.success(service.deploy(dto.getScopeType(),dto.getTargetId(),dto.getVersionId(),currentUserId()));
    }
    @PostMapping("/devices/{deviceId}/versions/{versionId}")
    public ApiResponseVO<SkillDeploymentVO> install(@PathVariable Long deviceId,@PathVariable Long versionId){return ApiResponseVO.success(service.deploy("GLOBAL",deviceId,versionId,currentUserId()));}
    @PostMapping("/{deploymentId}/remove")
    public ApiResponseVO<SkillDeploymentVO> remove(@PathVariable Long deploymentId){return ApiResponseVO.success(service.remove(deploymentId,currentUserId()));}
    private Long currentUserId(){return UserContext.requireCurrentUser().getId();}
}
