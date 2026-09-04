package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.EnrollmentRequestDTO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.EnrollmentResultVO;
import com.myharness.codex.service.AgentEnrollmentService;
import com.myharness.codex.service.AgentSkillDownloadService;
import com.myharness.codex.entity.vo.SkillFileVO;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.io.IOException;

@Validated
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {
    private final AgentEnrollmentService enrollmentService;
    private final AgentSkillDownloadService skillDownloadService;

    public AgentController(AgentEnrollmentService enrollmentService, AgentSkillDownloadService skillDownloadService) {
        this.enrollmentService = enrollmentService;
        this.skillDownloadService = skillDownloadService;
    }

    @PostMapping("/enroll")
    public ApiResponseVO<EnrollmentResultVO> enroll(@Valid @RequestBody EnrollmentRequestDTO dto) {
        return ApiResponseVO.success("设备注册成功", enrollmentService.enroll(dto));
    }

    @GetMapping("/skill-versions/{skillVersionId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long skillVersionId,
                                             @RequestHeader("X-Harness-Device-Code") String deviceCode,
                                             @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization)
            throws IOException {
        SkillFileVO file = skillDownloadService.download(skillVersionId,deviceCode,authorization);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=skill-" + skillVersionId + ".zip")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(file.getContentLength())
                .body(file.getResource());
    }
}
