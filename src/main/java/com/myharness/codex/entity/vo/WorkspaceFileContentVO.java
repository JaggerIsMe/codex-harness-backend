package com.myharness.codex.entity.vo;
import org.springframework.core.io.Resource;
public record WorkspaceFileContentVO(Resource resource, String fileName, long sizeBytes) {}
