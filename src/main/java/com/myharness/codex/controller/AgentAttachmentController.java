package com.myharness.codex.controller;

import com.myharness.codex.entity.vo.*;
import com.myharness.codex.service.ConversationAttachmentService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/agent/turns/{tid}/attachments")
public class AgentAttachmentController {
    private final ConversationAttachmentService service;
    public AgentAttachmentController(ConversationAttachmentService service){this.service=service;}
    @GetMapping
    public ApiResponseVO<List<ConversationAttachmentVO>> manifest(@PathVariable Long tid,
            @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        return ApiResponseVO.success(service.agentManifest(tid,code,auth));
    }
    @GetMapping("/{aid}/download")
    public ResponseEntity<Resource> download(@PathVariable Long tid,@PathVariable Long aid,
            @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) throws IOException {
        return ConversationAttachmentController.fileResponse(service.agentDownload(tid,aid,code,auth));
    }
}
