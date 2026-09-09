package com.myharness.codex.controller;

import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ConversationAttachmentService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{pid}/conversations/{cid}/attachments")
public class ConversationAttachmentController {
    private final ConversationAttachmentService service;

    public ConversationAttachmentController(ConversationAttachmentService service) {
        this.service = service;
    }

    @GetMapping("/limits")
    public ApiResponseVO<AttachmentLimitsVO> limits(@PathVariable Long pid, @PathVariable Long cid) {
        return ApiResponseVO.success(service.limits(pid, cid, user()));
    }

    @GetMapping
    public ApiResponseVO<List<ConversationAttachmentVO>> pending(@PathVariable Long pid, @PathVariable Long cid) {
        return ApiResponseVO.success(service.pending(pid, cid, user()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseVO<ConversationAttachmentVO> upload(@PathVariable Long pid, @PathVariable Long cid, @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponseVO.success(service.upload(pid, cid, user(), file));
    }

    @DeleteMapping("/{aid}")
    public ApiResponseVO<Void> delete(@PathVariable Long pid, @PathVariable Long cid, @PathVariable Long aid) throws IOException {
        service.delete(pid, cid, aid, user());
        return ApiResponseVO.success(null);
    }

    private Long user() {
        return UserContext.requireCurrentUser().getId();
    }
}
