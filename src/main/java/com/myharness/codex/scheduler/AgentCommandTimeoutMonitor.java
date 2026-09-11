package com.myharness.codex.scheduler;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.mapper.ConversationMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class AgentCommandTimeoutMonitor {
    private com.myharness.codex.service.WorkspaceFileService workspaceFiles;
    private com.myharness.codex.mapper.ProjectMapper projects;
    @org.springframework.beans.factory.annotation.Autowired
    public void setWorkspaceFiles(com.myharness.codex.service.WorkspaceFileService files,com.myharness.codex.mapper.ProjectMapper projects) {
        this.workspaceFiles=files;this.projects=projects;
    }
    private final AgentProperties properties;
    private final ConversationMapper conversations;
    private final com.myharness.codex.mapper.AgentDeviceMapper devices;

    public AgentCommandTimeoutMonitor(AgentProperties properties, ConversationMapper conversations,com.myharness.codex.mapper.AgentDeviceMapper devices) {
        this.properties = properties;
        this.conversations = conversations;
        this.devices=devices;
    }

    @Scheduled(fixedDelay = 5000L)
    @Transactional
    public void expireCommands() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.minusSeconds(properties.getCommandTimeoutSeconds());
        var changed=projects==null ? java.util.List.<com.myharness.codex.entity.po.ProjectPO>of() : projects.timedOutTurnProjects(deadline,now);
        conversations.failTimedOutThreadStarts(deadline);
        conversations.failTimedOutTurnStarts(deadline, now);
        devices.failTimedOutWorkspaces(properties.getCommandTimeoutSeconds());
        if(workspaceFiles!=null && !changed.isEmpty()) {
            Runnable refresh=() -> changed.forEach(p -> workspaceFiles.refreshProject(p.getId(),p.getUserId()));
            if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {refresh.run();}
                });
            else refresh.run();
        }
    }
}
