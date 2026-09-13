package com.myharness.codex.entity.dto;

import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

/** Immutable user-authored graph, including editor coordinates. Validated as a whole. */
public record WorkflowDTO(int schemaVersion, String startNodeId, List<Node> nodes) {
    public record Node(String id, String kind, String name, Long expertId, String objective,
                       String next, Condition condition, double x, double y,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<Input> inputs,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) JsonNode outputSchema,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<FileReference> inputFiles,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<FileReference> outputFiles) {
        public Node(String id,String kind,String name,Long expertId,String objective,String next,Condition condition,double x,double y) {
            this(id,kind,name,expertId,objective,next,condition,x,y,null,null,null,null);
        }
    }
    public record Input(String name,String source,String sourceNodeId,String pointer,String value,String fileName,boolean required) {}
    /** A project-relative location, not an attachment ID or proof that a file was generated. */
    public record FileReference(String name,String path,String sourceNodeId,String sourceFile) {}
    public record Condition(String sourceNodeId, String operator, String pointer, String value,
                            String whenTrue, String whenFalse) {}
}
