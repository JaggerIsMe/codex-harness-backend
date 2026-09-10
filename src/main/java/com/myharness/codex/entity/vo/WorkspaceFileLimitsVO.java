package com.myharness.codex.entity.vo;
public record WorkspaceFileLimitsVO(int maxArchiveFiles,long maxArchiveSourceBytes,long maxArchiveOutputBytes,
                                    long maxFileBytes,int maxRequestBytes) {}
