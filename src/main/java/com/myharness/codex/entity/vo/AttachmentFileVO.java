package com.myharness.codex.entity.vo;
import org.springframework.core.io.Resource;
public record AttachmentFileVO(Resource resource, String fileName, long sizeBytes) {}
