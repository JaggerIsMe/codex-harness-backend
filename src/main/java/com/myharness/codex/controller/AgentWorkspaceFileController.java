package com.myharness.codex.controller;

import com.myharness.codex.entity.vo.WorkspaceFileManifestVO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.service.WorkspaceFileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/agent/workspace-file-operations/{id}")
public class AgentWorkspaceFileController {
    private final WorkspaceFileService files;
    public AgentWorkspaceFileController(WorkspaceFileService files) {this.files=files;}
    @GetMapping
    public ApiResponseVO<WorkspaceFileManifestVO> manifest(@PathVariable Long id,
            @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        return ApiResponseVO.success(new WorkspaceFileManifestVO(files.agentManifest(id,code,auth)));
    }
    @GetMapping("/content")
    public ResponseEntity<Resource> content(@PathVariable Long id,
            @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) throws IOException {
        return WorkspaceFileResponses.download(files.agentContent(id,code,auth));
    }
    @PutMapping(value="/content",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ApiResponseVO<Void> receive(@PathVariable Long id,@RequestHeader("X-Harness-Device-Code") String code,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String auth,@RequestHeader("X-Content-SHA256") String sha,HttpServletRequest request) throws IOException {
        files.receiveContent(id,code,auth,sha,request.getInputStream());return ApiResponseVO.success(null);
    }
    @PutMapping(value="/items",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ApiResponseVO<Void> items(@PathVariable Long id,@RequestHeader("X-Harness-Device-Code") String code,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String auth,@RequestHeader("X-Content-SHA256") String sha,HttpServletRequest request) throws IOException {
        files.receiveItems(id,code,auth,sha,request.getInputStream());return ApiResponseVO.success(null);
    }
}
