package com.myharness.codex.scheduler;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.service.AgentEventService;
import com.myharness.codex.websocket.AgentConnectionRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AgentHeartbeatMonitor {
    private final AgentProperties properties;
    private final AgentDeviceMapper deviceMapper;
    private final AgentConnectionRegistry connections;
    private final AgentEventService eventService;

    public AgentHeartbeatMonitor(AgentProperties properties,AgentDeviceMapper deviceMapper,
                                 AgentConnectionRegistry connections,AgentEventService eventService) {
        this.properties=properties; this.deviceMapper=deviceMapper; this.connections=connections; this.eventService=eventService;
    }

    @Scheduled(fixedDelayString="#{${harness.agent.offline-scan-interval-seconds:15} * 1000}")
    public void scan() {
        LocalDateTime deadline=LocalDateTime.now().minusSeconds(properties.getHeartbeatTimeoutSeconds());
        for (AgentDevicePO device : deviceMapper.selectTimedOut(deadline)) {
            connections.disconnect(device.getDeviceCode(),"Heartbeat timeout");
            eventService.disconnected(device.getId());
        }
    }
}
