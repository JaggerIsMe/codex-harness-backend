package com.myharness.codex.entity.vo;

import com.myharness.codex.entity.po.AgentDevicePO;
import java.time.LocalDateTime;

public class AgentDeviceVO {
    private final Long id; private final String deviceCode; private final String deviceName; private final String status;
    private final String agentVersion; private final String osName; private final String osVersion; private final String isolationMode; private final Boolean managedModels; private final Boolean modelRuntimeTargets; private final LocalDateTime lastHeartbeatAt;
    public AgentDeviceVO(AgentDevicePO value) {
        id=value.getId(); deviceCode=value.getDeviceCode(); deviceName=value.getDeviceName(); status=value.getStatus();
        agentVersion=value.getAgentVersion(); osName=value.getOsName(); osVersion=value.getOsVersion(); isolationMode=value.getIsolationMode(); managedModels=value.getManagedModels(); modelRuntimeTargets=value.getModelRuntimeTargets(); lastHeartbeatAt=value.getLastHeartbeatAt();
    }
    public Long getId(){return id;} public String getDeviceCode(){return deviceCode;} public String getDeviceName(){return deviceName;}
    public String getStatus(){return status;} public String getAgentVersion(){return agentVersion;} public String getOsName(){return osName;}
    public String getOsVersion(){return osVersion;} public LocalDateTime getLastHeartbeatAt(){return lastHeartbeatAt;}
    public String getIsolationMode(){return isolationMode;}
    public Boolean getManagedModels(){return managedModels;}
    public Boolean getModelRuntimeTargets(){return modelRuntimeTargets;}
}
