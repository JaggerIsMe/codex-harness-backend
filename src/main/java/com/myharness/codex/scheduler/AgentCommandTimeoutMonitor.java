package com.myharness.codex.scheduler;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.mapper.ConversationMapper;
import com.myharness.codex.mapper.SkillMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class AgentCommandTimeoutMonitor {
    private final AgentProperties properties;
    private final ConversationMapper conversations;
    private final SkillMapper skills;

    public AgentCommandTimeoutMonitor(AgentProperties properties, ConversationMapper conversations, SkillMapper skills) {
        this.properties = properties;
        this.conversations = conversations;
        this.skills = skills;
    }

    @Scheduled(fixedDelay = 5000L)
    @Transactional
    public void expireCommands() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.minusSeconds(properties.getCommandTimeoutSeconds());
        conversations.failTimedOutThreadStarts(deadline);
        conversations.failTimedOutTurnStarts(deadline, now);
        skills.failTimedOutDeployments(deadline);
    }
}
