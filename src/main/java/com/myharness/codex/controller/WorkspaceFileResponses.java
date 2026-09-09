package com.myharness.codex.controller;

import com.myharness.codex.entity.vo.WorkspaceFileContentVO;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import java.nio.charset.StandardCharsets;

final class WorkspaceFileResponses {
    private WorkspaceFileResponses() {}

    static ResponseEntity<Resource> download(WorkspaceFileContentVO file) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(file.resource());
    }
}
