package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.CreateProjectDTO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.ProjectVO;
import com.myharness.codex.entity.vo.PageVO;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ProjectService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService service;
    public ProjectController(ProjectService service){this.service=service;}
    @PostMapping public ApiResponseVO<ProjectVO> create(@Valid @RequestBody CreateProjectDTO dto){
        return ApiResponseVO.success(service.createProject(dto,UserContext.requireCurrentUser().getId()));
    }
    @GetMapping public ApiResponseVO<PageVO<ProjectVO>> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size,@RequestParam(required=false) String keyword){
        return ApiResponseVO.success(service.getProjects(UserContext.requireCurrentUser().getId(),page,size,keyword));
    }
    @PostMapping("/{id}/retry-preparation") public ApiResponseVO<ProjectVO> retry(@PathVariable Long id){
        return ApiResponseVO.success(service.retryPreparation(id,UserContext.requireCurrentUser().getId()));
    }
    @GetMapping("/{id}") public ApiResponseVO<ProjectVO> get(@PathVariable Long id){
        return ApiResponseVO.success(service.getProject(id,UserContext.requireCurrentUser().getId()));
    }
}
