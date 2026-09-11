package com.myharness.codex.controller;
import com.myharness.codex.entity.dto.SkillExpertAssignmentDTO;
import com.myharness.codex.entity.dto.SkillExpertBatchAssignmentDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ExpertSkillAssignmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1")
public class SkillExpertAssignmentController {
    private final ExpertSkillAssignmentService service;
    public SkillExpertAssignmentController(ExpertSkillAssignmentService service) {this.service=service;}
    private Long user() {return UserContext.requireCurrentUser().getId();}
    @GetMapping("/skills/{skillId}/versions/{versionId}/expert-candidates")
    public ApiResponseVO<SkillExpertAssignmentVO.Page> candidates(@PathVariable Long skillId,@PathVariable Long versionId,
            @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponseVO.success(service.candidates(skillId,versionId,keyword,page,size,user()));
    }
    @PostMapping("/skill-expert-assignments/preview")
    public ApiResponseVO<SkillExpertAssignmentVO.Preview> preview(@Valid @RequestBody SkillExpertAssignmentDTO input) {return ApiResponseVO.success(service.preview(input,user()));}
    @PostMapping("/skill-expert-assignments/batch/candidates")
    public ApiResponseVO<SkillExpertAssignmentVO.Page> batchCandidates(@Valid @RequestBody SkillExpertBatchAssignmentDTO.Query input) {
        return ApiResponseVO.success(service.candidates(input.targets(),input.keyword(),input.page(),input.size(),user()));
    }
    @PostMapping("/skill-expert-assignments/batch/preview")
    public ApiResponseVO<SkillExpertAssignmentVO.Preview> batchPreview(@Valid @RequestBody SkillExpertBatchAssignmentDTO input) {
        return ApiResponseVO.success(service.preview(input,user()));
    }
    @PostMapping("/skill-expert-assignments/{id}/commit")
    public ApiResponseVO<SkillExpertAssignmentVO.Submission> commit(@PathVariable String id) {return ApiResponseVO.success(service.commit(id,user()));}
    @GetMapping("/skill-expert-assignments/{id}")
    public ApiResponseVO<SkillExpertAssignmentVO.Submission> result(@PathVariable String id) {return ApiResponseVO.success(service.submission(id,user()));}
    @GetMapping("/skill-expert-assignments")
    public ApiResponseVO<List<SkillExpertAssignmentVO.History>> history(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return ApiResponseVO.success(service.history(page,size,user()));}
}
