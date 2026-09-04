package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.SkillVersionStatusDTO;
import com.myharness.codex.entity.dto.UpdateSkillDTO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.SkillFileVO;
import com.myharness.codex.entity.vo.SkillVO;
import com.myharness.codex.entity.vo.SkillVersionVO;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.SkillService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {
    private final SkillService service;
    public SkillController(SkillService service) { this.service = service; }

    @GetMapping
    public ApiResponseVO<List<SkillVO>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status) {
        return ApiResponseVO.success(service.list(keyword, status));
    }
    @GetMapping("/{skillId}")
    public ApiResponseVO<SkillVO> get(@PathVariable Long skillId) { return ApiResponseVO.success(service.get(skillId)); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseVO<SkillVO> create(@RequestParam String skillName,@RequestParam(required = false) String description,
                                         @RequestParam String version,@RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponseVO.success("Skill 上传成功", service.create(skillName, description, version, file,
                UserContext.requireCurrentUser().getId()));
    }
    @PostMapping(value = "/{skillId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseVO<SkillVersionVO> uploadVersion(@PathVariable Long skillId,@RequestParam String version,
                                                        @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponseVO.success("Skill 版本上传成功", service.uploadVersion(skillId, version, file,
                UserContext.requireCurrentUser().getId()));
    }
    @PutMapping("/{skillId}")
    public ApiResponseVO<SkillVO> update(@PathVariable Long skillId,@Valid @RequestBody UpdateSkillDTO dto) {
        return ApiResponseVO.success(service.update(skillId, dto));
    }
    @PatchMapping("/{skillId}/versions/{versionId}/status")
    public ApiResponseVO<SkillVersionVO> updateVersionStatus(@PathVariable Long skillId,@PathVariable Long versionId,
                                                              @Valid @RequestBody SkillVersionStatusDTO dto) {
        return ApiResponseVO.success(service.updateVersionStatus(skillId, versionId, dto));
    }
    @GetMapping("/{skillId}/versions/{versionId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long skillId,@PathVariable Long versionId) throws IOException {
        SkillFileVO file = service.download(skillId, versionId);
        String disposition = ContentDisposition.attachment().filename(file.getFilename(), StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.parseMediaType("application/zip")).contentLength(file.getContentLength()).body(file.getResource());
    }
}
