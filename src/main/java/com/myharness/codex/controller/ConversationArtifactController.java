package com.myharness.codex.controller;

import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ConversationArtifactService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{pid}/conversations/{cid}/artifacts")
public class ConversationArtifactController {
    private final ConversationArtifactService service;
    public ConversationArtifactController(ConversationArtifactService service){this.service=service;}
    @GetMapping
    public ApiResponseVO<List<ConversationArtifactVO>> list(@PathVariable Long pid,@PathVariable Long cid) {
        return ApiResponseVO.success(service.list(pid,cid,user()));
    }
    @PostMapping("/{aid}/retry")
    public ApiResponseVO<ConversationArtifactVO> retry(@PathVariable Long pid,@PathVariable Long cid,@PathVariable Long aid) {
        return ApiResponseVO.success(service.retry(pid,cid,aid,user()));
    }
    @GetMapping("/{aid}/download")
    public ResponseEntity<Resource> download(@PathVariable Long pid,@PathVariable Long cid,@PathVariable Long aid) throws IOException {
        return ConversationAttachmentController.fileResponse(service.download(pid,cid,aid,user()));
    }
    private Long user(){return UserContext.requireCurrentUser().getId();}
}
