package com.myharness.codex.service.impl;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.entity.po.SkillDeploymentPO;
import com.myharness.codex.gateway.AgentCommand;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.ProjectMapper;
import com.myharness.codex.mapper.SkillMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillDeploymentServiceImplTest {
    @Test
    void deploysProjectSkillToItsOwnedWorkspace() {
        SkillMapper skills = mock(SkillMapper.class);
        ProjectMapper projects = mock(ProjectMapper.class);
        AgentCommandGateway gateway = mock(AgentCommandGateway.class);
        ProjectPO project = new ProjectPO(); project.setId(9L); project.setUserId(3L); project.setDeviceId(2L);
        project.setProjectName("订单服务"); project.setStatus("ACTIVE"); project.setWorkspaceName("order-service");
        project.setWorkspaceStatus("ENABLED"); project.setRootPath("D:/projects/order-service");
        when(projects.selectOwned(9L, 3L)).thenReturn(project);
        SkillDeploymentPO deployment = deployment();
        when(skills.selectDeployable(2L, 11L, "PROJECT", 9L, "PROJECT:9")).thenReturn(deployment);
        doAnswer(invocation -> { deployment.setId(15L); return 1; }).when(skills).upsertDeployment(deployment);
        when(skills.selectDeployment(15L)).thenReturn(deployment);
        when(gateway.isOnline("device-2")).thenReturn(true);
        AgentProperties properties = new AgentProperties(); properties.setPublicBaseUrl("http://localhost:9010");
        SkillDeploymentServiceImpl service = new SkillDeploymentServiceImpl(skills, projects, gateway, properties,
                mock(com.myharness.codex.security.AuthorizationService.class));

        service.deploy("PROJECT", 9L, 11L, 3L);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(gateway).send(org.mockito.ArgumentMatchers.eq("device-2"), command.capture());
        @SuppressWarnings("unchecked") Map<String,Object> payload = (Map<String,Object>) command.getValue().getPayload();
        assertEquals("PROJECT", payload.get("scopeType"));
        assertEquals("order-service", payload.get("workspaceName"));
        assertEquals("15", command.getValue().getCorrelationId());
    }

    private SkillDeploymentPO deployment() {
        SkillDeploymentPO value = new SkillDeploymentPO(); value.setDeviceId(2L); value.setSkillVersionId(11L);
        value.setSkillId(7L); value.setSkillName("code-review"); value.setVersion("1.0.0");
        value.setSha256(new String(new char[64]).replace('\0', 'a')); value.setDeviceCode("device-2");
        value.setDeviceName("build-agent"); value.setScopeType("PROJECT"); value.setProjectId(9L); value.setScopeKey("PROJECT:9");
        value.setInstallStatus("INSTALLING"); return value;
    }
}
