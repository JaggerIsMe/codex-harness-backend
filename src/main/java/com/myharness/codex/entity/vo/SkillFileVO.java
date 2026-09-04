package com.myharness.codex.entity.vo;

import org.springframework.core.io.Resource;

public class SkillFileVO {
    private final Resource resource;
    private final long contentLength;
    private final String filename;
    public SkillFileVO(Resource resource,long contentLength) { this(resource, contentLength, "skill.zip"); }
    public SkillFileVO(Resource resource,long contentLength,String filename) { this.resource=resource; this.contentLength=contentLength; this.filename=filename; }
    public Resource getResource(){return resource;} public long getContentLength(){return contentLength;}
    public String getFilename(){return filename;}
}
