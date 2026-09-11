package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.SkillImportDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.SkillImportService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/skills/imports")
public class SkillImportController {
    private final SkillImportService service;
    public SkillImportController(SkillImportService service) { this.service = service; }
    @PostMapping(value="/files", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseVO<SkillImportVO.Upload> upload(@RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponseVO.success(service.upload(file,user()));
    }
    @DeleteMapping("/files/{id}")
    public ApiResponseVO<Void> discard(@PathVariable String id) { service.discard(id,user()); return ApiResponseVO.success(null); }
    @PostMapping("/preview")
    public ApiResponseVO<SkillImportVO.Preview> preview(@Valid @RequestBody SkillImportDTO input) {
        return ApiResponseVO.success(service.preview(input,user()));
    }
    @PostMapping("/commit")
    public ApiResponseVO<SkillImportVO.Submission> commit(@Valid @RequestBody SkillImportDTO.Commit input) {
        return ApiResponseVO.success(service.commit(input,user()));
    }
    @GetMapping("/submissions/{id}")
    public ApiResponseVO<SkillImportVO.Submission> submission(@PathVariable String id) {
        return ApiResponseVO.success(service.submission(id,user()));
    }
    private Long user() { return UserContext.requireCurrentUser().getId(); }
}
