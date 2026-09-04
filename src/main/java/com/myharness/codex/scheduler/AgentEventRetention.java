package com.myharness.codex.scheduler;

import com.myharness.codex.mapper.AgentDeviceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AgentEventRetention {
    private final AgentDeviceMapper mapper;
    private final int days;

    public AgentEventRetention(AgentDeviceMapper mapper, @Value("${harness.message-stream.event-retention-days:7}") int days) {
        if (days < 1) throw new IllegalArgumentException("Event retention must be at least one day");
        this.mapper = mapper;
        this.days = days;
    }

    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        mapper.deleteExpiredEvents(LocalDateTime.now().minusDays(days));
    }
}
