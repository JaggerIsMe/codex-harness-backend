package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.UpdateConversationDTO;
import com.myharness.codex.entity.dto.UpdateProjectDTO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.ConversationVO;
import com.myharness.codex.entity.vo.ProjectVO;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ProjectManagementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectManagementController {
    private final ProjectManagementService service;
    public ProjectManagementController(ProjectManagementService service) {this.service=service;}

    @PutMapping("/{id}")
    public ApiResponseVO<ProjectVO> updateProject(@PathVariable Long id,@Valid @RequestBody UpdateProjectDTO input) {
        return ApiResponseVO.success(service.updateProject(id,input,UserContext.requireCurrentUser().getId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponseVO<Void> deleteProject(@PathVariable Long id) {
        service.deleteProject(id,UserContext.requireCurrentUser().getId());
        return ApiResponseVO.success(null);
    }

    @PutMapping("/{projectId}/conversations/{id}")
    public ApiResponseVO<ConversationVO> updateConversation(@PathVariable Long projectId,@PathVariable Long id,
            @Valid @RequestBody UpdateConversationDTO input) {
        return ApiResponseVO.success(service.updateConversation(projectId,id,input,UserContext.requireCurrentUser().getId()));
    }

    @DeleteMapping("/{projectId}/conversations/{id}")
    public ApiResponseVO<Void> deleteConversation(@PathVariable Long projectId,@PathVariable Long id) {
        service.deleteConversation(projectId,id,UserContext.requireCurrentUser().getId());
        return ApiResponseVO.success(null);
    }
}
