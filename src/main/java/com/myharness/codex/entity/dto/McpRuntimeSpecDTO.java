package com.myharness.codex.entity.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;
import java.util.Map;

public class McpRuntimeSpecDTO {
    private String transportType;
    private String command;
    private List<String> args = List.of();
    private String cwdMode;
    private List<String> envVars = List.of();
    private String url;
    private Map<String,String> httpHeaders = Map.of();
    private int startupTimeoutSeconds = 10;
    private int toolTimeoutSeconds = 60;
    private boolean required = true;
    private List<String> enabledTools = List.of();
    private List<String> disabledTools = List.of();

    public String getTransportType() { return transportType; }
    public void setTransportType(String value) { transportType=value; }
    public String getCommand() { return command; }
    public void setCommand(String value) { command=value; }
    public List<String> getArgs() { return args; }
    public void setArgs(List<String> value) { args=value; }
    public String getCwdMode() { return cwdMode; }
    public void setCwdMode(String value) { cwdMode=value; }
    public List<String> getEnvVars() { return envVars; }
    public void setEnvVars(List<String> value) { envVars=value; }
    public String getUrl() { return url; }
    public void setUrl(String value) { url=value; }
    public Map<String,String> getHttpHeaders() { return httpHeaders; }
    public void setHttpHeaders(Map<String,String> value) { httpHeaders=value; }
    public int getStartupTimeoutSeconds() { return startupTimeoutSeconds; }
    public void setStartupTimeoutSeconds(int value) { startupTimeoutSeconds=value; }
    public int getToolTimeoutSeconds() { return toolTimeoutSeconds; }
    public void setToolTimeoutSeconds(int value) { toolTimeoutSeconds=value; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean value) { required=value; }
    public List<String> getEnabledTools() { return enabledTools; }
    public void setEnabledTools(List<String> value) { enabledTools=value; }
    public List<String> getDisabledTools() { return disabledTools; }
    public void setDisabledTools(List<String> value) { disabledTools=value; }

    @JsonAnySetter
    public void rejectUnknownField(String name,Object value) {
        throw new IllegalArgumentException("不支持的 MCP 配置字段："+name);
    }
}
