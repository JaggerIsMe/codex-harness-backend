package com.myharness.codex.entity.vo;

public record WorkspaceFilePreviewVO(String operationId, String path, String fileName, String kind,
        String mediaType, String encoding, long sizeBytes, String sha256, String readyAt,
        Integer width, Integer height, String reason, Limits limits) {
    public record Limits(long maxBytes, int maxLines, int maxRows, int maxColumns, long maxPixels) {}
}
