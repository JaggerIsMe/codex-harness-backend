package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

public class McpConfigurationDTO extends McpRuntimeSpecDTO {
    private @NotBlank @Size(max=64) String serverCode;
    private @NotBlank @Size(max=128) String name;
    private @Size(max=2000) String description;
    private @PositiveOrZero Long revision;

    public String getServerCode() { return serverCode; }
    public void setServerCode(String value) { serverCode=value; }
    public String getName() { return name; }
    public void setName(String value) { name=value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { description=value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { revision=value; }

    @Override public void setArgs(List<String> value) { super.setArgs(value); }
    @Override public void setEnvVars(List<String> value) { super.setEnvVars(value); }
    @Override public void setHttpHeaders(Map<String,String> value) { super.setHttpHeaders(value); }
}
