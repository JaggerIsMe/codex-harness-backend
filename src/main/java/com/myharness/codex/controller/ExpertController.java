package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ExpertService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ExpertController {
    private final ExpertService service;
    public ExpertController(ExpertService service) {this.service=service;}
    private Long user() {return UserContext.requireCurrentUser().getId();}
    @GetMapping("/admin/experts") public ApiResponseVO<List<ExpertVO>> admin(@RequestParam(defaultValue="") String keyword) {return ApiResponseVO.success(service.list(keyword,true,user()));}
    @GetMapping("/expert-market") public ApiResponseVO<List<ExpertVO>> market(@RequestParam(defaultValue="") String keyword) {return ApiResponseVO.success(service.list(keyword,false,user()));}
    @PostMapping("/admin/experts") public ApiResponseVO<ExpertVO> create(@Valid @RequestBody ExpertDraftDTO dto) {return ApiResponseVO.success(service.save(null,dto,user()));}
    @PutMapping("/admin/experts/{id}/draft") public ApiResponseVO<ExpertVO> edit(@PathVariable Long id,@Valid @RequestBody ExpertDraftDTO dto) {return ApiResponseVO.success(service.save(id,dto,user()));}
    @PostMapping("/admin/experts/{id}/publish") public ApiResponseVO<ExpertVO> publish(@PathVariable Long id,@Valid @RequestBody ExpertPublishDTO dto) {return ApiResponseVO.success(service.publish(id,dto.getRevision(),dto.getCompatibleUpgrade(),user()));}
    @PostMapping("/admin/experts/{id}/unpublish") public ApiResponseVO<ExpertVO> unpublish(@PathVariable Long id,@Valid @RequestBody ExpertRevisionDTO dto) {return ApiResponseVO.success(service.status(id,"UNPUBLISHED",dto.getRevision(),user()));}
    @PostMapping("/admin/experts/{id}/disable") public ApiResponseVO<ExpertVO> disable(@PathVariable Long id,@Valid @RequestBody ExpertRevisionDTO dto) {return ApiResponseVO.success(service.status(id,"DISABLED",dto.getRevision(),user()));}
    @GetMapping("/expert-market/{id}/versions") public ApiResponseVO<List<ExpertVersionVO>> versions(@PathVariable Long id) {return ApiResponseVO.success(service.versions(id,user()));}
    @GetMapping("/projects/{projectId}/experts") public ApiResponseVO<ProjectExpertsVO> project(@PathVariable Long projectId) {return ApiResponseVO.success(service.projectExperts(projectId,user()));}
    @PostMapping("/projects/{projectId}/experts") public ApiResponseVO<ProjectExpertsVO> bind(@PathVariable Long projectId,@Valid @RequestBody ExpertBindingDTO dto) {return ApiResponseVO.success(service.bind(projectId,dto,user()));}
    @DeleteMapping("/projects/{projectId}/experts/{id}") public ApiResponseVO<ProjectExpertsVO> unbind(@PathVariable Long projectId,@PathVariable Long id,@Valid @RequestBody ExpertRevisionDTO dto) {return ApiResponseVO.success(service.unbind(projectId,id,dto.getRevision(),user()));}
    @GetMapping("/projects/{projectId}/conversations/{id}/expert") public ApiResponseVO<ExpertSelectionVO> selection(@PathVariable Long projectId,@PathVariable Long id) {return ApiResponseVO.success(service.selection(projectId,id,user()));}
    @GetMapping("/projects/{projectId}/conversations/{id}/turn-experts") public ApiResponseVO<List<TurnExpertVO>> turns(@PathVariable Long projectId,@PathVariable Long id) {return ApiResponseVO.success(service.turnExperts(projectId,id,user()));}
}
