package com.myharness.codex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExpertMcpUpdateTest {
    ExpertMapper experts;
    McpConfigurationMapper configurations;
    ExpertService service;
    McpConfigurationService mcp;
    ObjectMapper json = new ObjectMapper();
    Map<Long, McpConfigurationVersionPO> mcpVersions = new HashMap<>();
    Map<Long, ExpertVersionPO> expertVersions = new HashMap<>();
    ExpertPO expert;
    ConversationPO conversation;
    ProjectExpertPO binding;

    @BeforeEach void setup() {
        experts=mock(ExpertMapper.class);configurations=mock(McpConfigurationMapper.class);
        var access=mock(AuthorizationService.class);var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        mcp=new McpConfigurationService(configurations,access,tx,json);
        var projects=mock(ProjectMapper.class);var conversations=mock(ConversationMapper.class);
        var devices=mock(AgentDeviceMapper.class);var rbac=mock(RbacMapper.class);
        service=new ExpertService(experts,projects,conversations,mock(SkillMapper.class),devices,rbac,access,tx,json,new AgentProperties(),mcp);
        var project=new ProjectPO();project.setId(1L);project.setUserId(2L);project.setDeviceId(8L);project.setStatus("ACTIVE");
        when(projects.selectOwned(1L,2L)).thenReturn(project);
        var device=new AgentDevicePO();device.setProjectExperts(true);device.setExpertMcp(true);when(devices.selectById(8L)).thenReturn(device);
        when(rbac.expertAssigned(2L,10L)).thenReturn(1);
        conversation=new ConversationPO();conversation.setId(3L);conversation.setProjectId(1L);conversation.setUserId(2L);conversation.setDeviceId(8L);
        conversation.setSelectedExpertId(10L);conversation.setSelectedExpertVersionId(100L);
        when(conversations.selectOwnedConversation(1L,3L,2L)).thenReturn(conversation);when(conversations.lockConversation(3L)).thenReturn(conversation);
        binding=new ProjectExpertPO();binding.setExpertId(10L);binding.setExpertVersionId(100L);binding.setStatus("PUBLISHED");
        binding.setVersionNo(1L);binding.setLatestVersionNo(1L);binding.setLatestVersionId(100L);
        when(experts.bindings(1L)).thenReturn(List.of(binding));
        expert=new ExpertPO();expert.setId(10L);expert.setName("Java 专家");expert.setSystemPrompt("Review Java");
        expert.setStatus("PUBLISHED");expert.setRevision(1L);expert.setPublishedVersionId(100L);
        expert.setSkillVersionIds("[]");expert.setMcpVersionIds("[70]");
        var published=new ExpertVersionPO();published.setId(100L);published.setExpertId(10L);published.setVersionNo(1L);
        published.setSkillVersionIds("[]");published.setMcpVersionIds("[70]");expertVersions.put(100L,published);
        published.setStatus("PUBLISHED");
        when(experts.list("")).thenReturn(List.of(expert));when(experts.get(10L)).thenReturn(expert);when(experts.lock(10L)).thenReturn(expert);
        when(experts.version(anyLong())).thenAnswer(call -> expertVersions.get(call.getArgument(0)));
        var old=new McpConfigurationVersionPO();old.setId(70L);old.setMcpConfigurationId(7L);old.setVersionNo(1L);
        old.setServerCode("github");old.setName("GitHub MCP");old.setVersionStatus("ACTIVE");old.setConfigurationStatus("ENABLED");
        old.setRuntimeSpec("{\"transportType\":\"STDIO\",\"command\":\"old-server\"}");mcpVersions.put(70L,old);
        when(configurations.version(anyLong())).thenAnswer(call -> mcpVersions.get(call.getArgument(0)));
        var configuration=new McpConfigurationPO();configuration.setId(7L);configuration.setRevision(1L);configuration.setStatus("ENABLED");
        configuration.setCurrentVersionId(70L);
        when(configurations.lock(7L)).thenReturn(configuration);when(configurations.get(7L)).thenReturn(configuration);
        when(configurations.nextVersion(7L)).thenReturn(2L);
        when(configurations.boundProjectsForConfiguration(7L)).thenReturn(List.of(1L,2L));
        doAnswer(call -> {mcpVersions.values().forEach(v -> v.setVersionStatus("REVOKED"));return 1;})
                .when(configurations).revokeActiveVersions(7L);
        doAnswer(call -> {var version=(McpConfigurationVersionPO)call.getArgument(0);version.setId(71L);
            version.setVersionStatus("ACTIVE");version.setConfigurationStatus("ENABLED");mcpVersions.put(71L,version);return 1;
        }).when(configurations).insertVersion(any());
    }

    @Test void publishingMcpVersionRevokesOldVersionAndNotifiesExpertAdmin() {
        assertTrue(service.list("",true,2L).getFirst().mcpUpdates().isEmpty());
        assertTrue(service.selection(1L,3L,2L).available());
        assertEquals(70L,service.freeze(conversation,1L).getMcpServers().getFirst().getConfigurationVersionId());
        var input=new McpConfigurationDTO();input.setName("GitHub MCP");input.setServerCode("github");input.setTransportType("STDIO");
        input.setCommand("new-server");input.setRevision(1L);
        mcp.update(7L,input,2L);

        assertThrows(BusinessException.class,() -> mcp.runtimes(List.of(70L)),"Previous MCP Configuration Version must be unavailable");
        var result=service.list("",true,2L).getFirst();

        assertEquals(1,result.mcpUpdates().size(),"Expert management must report a newer MCP Configuration Version");
        assertEquals(70L,result.mcpUpdates().getFirst().currentVersionId());
        assertEquals(71L,result.mcpUpdates().getFirst().availableVersionId());
        assertEquals("new-server",mcp.runtimes(List.of(71L)).getFirst().getCommand());
        assertEquals("[70]",expertVersions.get(100L).getMcpVersionIds());
        assertFalse(service.selection(1L,3L,2L).available());
        assertTrue(service.selection(1L,3L,2L).unavailableReason().contains("MCP"));
        assertThrows(BusinessException.class,() -> service.freeze(conversation,2L));
        var beforePublish=service.projectExperts(1L,2L).experts().getFirst();
        assertFalse(beforePublish.available());assertFalse(beforePublish.upgradeAvailable());
        var order=inOrder(configurations);
        order.verify(configurations).lock(7L);order.verify(configurations).lockProject(1L);order.verify(configurations).lockProject(2L);
        order.verify(configurations).revokeActiveVersions(7L);order.verify(configurations).insertVersion(any());
        order.verify(configurations).update(any());order.verify(configurations).bumpProject(1L);order.verify(configurations).bumpProject(2L);

        var draft=new ExpertDraftDTO();draft.setName("Java 专家");draft.setSystemPrompt("Review Java");draft.setRevision(1L);draft.setMcpBindings(List.of(71L));
        assertEquals(1,service.save(10L,draft,2L).mcpUpdates().size());
        when(experts.nextVersion(10L)).thenReturn(2L);
        doAnswer(call -> {var version=(ExpertVersionPO)call.getArgument(0);version.setId(101L);version.setStatus("PUBLISHED");
            expertVersions.put(101L,version);return 1;}).when(experts).publish(any());
        doAnswer(call -> {expert.setPublishedVersionId(101L);expert.setStatus("PUBLISHED");binding.setLatestVersionId(101L);
            binding.setLatestVersionNo(2L);return 1;}).when(experts).published(10L,101L);
        assertTrue(service.publish(10L,1L,true,2L).mcpUpdates().isEmpty());
        assertTrue(service.projectExperts(1L,2L).experts().getFirst().upgradeAvailable());
        assertThrows(BusinessException.class,() -> service.freeze(conversation,2L));
        assertEquals("[70]",expertVersions.get(100L).getMcpVersionIds());
        assertEquals("[71]",expertVersions.get(101L).getMcpVersionIds());
        conversation.setSelectedExpertVersionId(101L);binding.setExpertVersionId(101L);
        assertTrue(service.selection(1L,3L,2L).available());
        assertEquals(71L,service.freeze(conversation,2L).getMcpServers().getFirst().getConfigurationVersionId());
    }

    @Test void unavailableMcpReplacementIsNotAdvertisedAndNeverFallsBackToOlderVersion() {
        var latest=new McpConfigurationVersionPO();latest.setId(71L);latest.setVersionNo(2L);latest.setVersionStatus("REVOKED");
        mcpVersions.put(71L,latest);configurations.get(7L).setCurrentVersionId(71L);
        assertTrue(service.list("",true,2L).getFirst().mcpUpdates().isEmpty());
        assertThrows(BusinessException.class,() -> mcp.runtimes(List.of(71L)));
        latest.setVersionStatus("ACTIVE");mcpVersions.get(70L).setConfigurationStatus("DISABLED");
        assertTrue(service.list("",true,2L).getFirst().mcpUpdates().isEmpty());
        assertThrows(BusinessException.class,() -> mcp.runtimes(List.of(70L)));
    }

    @Test void selectableMcpVersionProvidesOldIdsForDraftUpgradeWithoutRuntimeDetails() {
        var current=mcpVersions.get(70L);
        when(configurations.selectableVersions()).thenReturn(List.of(current));
        when(configurations.previousVersionIds(7L,1L)).thenReturn(List.of(69L,68L));
        var selected=mcp.selectableVersions(2L).getFirst();
        assertEquals(70L,selected.versionId());assertEquals(List.of(69L,68L),selected.previousVersionIds());
        var response=json.valueToTree(selected);
        assertFalse(response.has("runtimeSpec"));assertFalse(response.has("httpHeaders"));
    }

    @Test void staleMcpRevisionDoesNotRevokeExistingVersions() {
        var input=new McpConfigurationDTO();input.setName("GitHub MCP");input.setServerCode("github");input.setTransportType("STDIO");
        input.setCommand("new-server");input.setRevision(0L);
        assertThrows(BusinessException.class,() -> mcp.update(7L,input,2L));
        verify(configurations,never()).revokeActiveVersions(anyLong());verify(configurations,never()).insertVersion(any());
        assertEquals("old-server",mcp.runtimes(List.of(70L)).getFirst().getCommand());
    }
}
