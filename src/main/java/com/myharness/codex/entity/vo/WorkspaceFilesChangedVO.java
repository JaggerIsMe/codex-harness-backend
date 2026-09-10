package com.myharness.codex.entity.vo;
public record WorkspaceFilesChangedVO(String type, Long deviceId, Payload payload) {
    public record Payload(String projectId,String path,String operationId,String kind,String status,String sourcePath,
                          String targetPath,String entryType,String entryRevision,java.util.List<String> affectedDirectories) {
        public Payload(String projectId,String path,String operationId) {this(projectId,path,operationId,null,null,null,null,null,null,java.util.List.of());}
    }
}
