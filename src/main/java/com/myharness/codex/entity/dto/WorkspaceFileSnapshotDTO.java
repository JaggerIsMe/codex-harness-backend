package com.myharness.codex.entity.dto;

import com.myharness.codex.entity.vo.WorkspaceFileContentVO;
import java.time.LocalDateTime;

/** Internal authorized transfer snapshot; never serialized by a Controller. */
public record WorkspaceFileSnapshotDTO(String operationId, String path, String sha256,
        LocalDateTime readyAt, WorkspaceFileContentVO file) {}
