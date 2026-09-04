package com.myharness.codex.service.impl;
import com.myharness.codex.entity.dto.CreateProjectDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectServiceImplTest {
    ProjectMapper projects;AgentDeviceMapper devices;AuthorizationService access;AgentCommandGateway gateway;
    PlatformTransactionManager manager;ProjectServiceImpl service;AgentWorkspacePO workspace;ProjectPO project;
    @BeforeEach void setup() {
        projects=mock(ProjectMapper.class);devices=mock(AgentDeviceMapper.class);access=mock(AuthorizationService.class);gateway=mock(AgentCommandGateway.class);
        manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new ProjectServiceImpl(projects,devices,access,gateway,new TransactionTemplate(manager));
        AgentDevicePO device=new AgentDevicePO();device.setId(2L);device.setDeviceCode("device-2");device.setStatus("ONLINE");device.setIsolationMode("WINDOWS_PROJECT_PROFILE");
        when(devices.selectById(2L)).thenReturn(device);when(gateway.isOnline("device-2")).thenReturn(true);
        AgentWorkspaceRootPO root=new AgentWorkspaceRootPO();root.setRootName("allowed");when(devices.selectWorkspaceRoots(2L)).thenReturn(List.of(root));
        doAnswer(i->{workspace=i.getArgument(0);workspace.setId(4L);workspace.setStatus("CREATING");return 1;}).when(devices).insertCreatingWorkspace(any());
        doAnswer(i->{project=i.getArgument(0);project.setId(7L);project.setStatus("ACTIVE");project.setWorkspaceStatus("CREATING");return 1;}).when(projects).insert(any());
        when(devices.selectWorkspace(4L,2L)).thenAnswer(i->workspace);
        when(projects.selectOwned(7L,3L)).thenAnswer(i->project);
    }
    CreateProjectDTO dto() {
        CreateProjectDTO dto=new CreateProjectDTO();dto.setProjectName("同名展示项目");dto.setDeviceId(2L);
        dto.setRequestKey("12345678-1234-1234-1234-123456789012");return dto;
    }
    @Test void persistsExclusiveWorkspaceBeforeDispatch() {
        var result=service.createProject(dto(),3L);
        assertEquals("PREPARING",result.getProvisioningStatus());
        assertEquals(4L,result.getWorkspaceId());assertTrue(workspace.getWorkspaceName().startsWith("u3-"));
        assertFalse(workspace.getWorkspaceName().contains(dto().getProjectName()));
        var order=inOrder(devices,projects,manager,gateway);
        order.verify(devices).insertCreatingWorkspace(any());order.verify(projects).insert(any());
        order.verify(manager).commit(any());order.verify(gateway).send(eq("device-2"),any());
    }
    @Test void repeatingCreateReturnsOriginalProjectWithoutAllocatingDirectory() {
        ProjectPO saved=new ProjectPO();saved.setId(7L);saved.setDeviceId(2L);saved.setProjectName(dto().getProjectName());
        when(projects.selectRequest(3L,dto().getRequestKey())).thenReturn(saved);
        assertEquals(7L,service.createProject(dto(),3L).getId());
        verify(devices,never()).insertCreatingWorkspace(any());verify(gateway,never()).send(any(),any());
    }
    @Test void rejectsUnauthorizedMachineBeforeAllocating() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(access).requireDevice(3L,2L);
        assertThrows(BusinessException.class,()->service.createProject(dto(),3L));
        verify(devices,never()).insertCreatingWorkspace(any());verify(gateway,never()).send(any(),any());
    }
    @Test void legacyAgentCannotProvisionPrivateProject() {
        devices.selectById(2L).setIsolationMode("WINDOWS_ELEVATED");
        assertThrows(BusinessException.class,()->service.createProject(dto(),3L));
        verify(devices,never()).insertCreatingWorkspace(any());verify(gateway,never()).send(any(),any());
    }
    @Test void sameMachineAndDisplayNameStillAllocateDifferentUserDirectories() {
        service.createProject(dto(),3L);String first=workspace.getWorkspaceName();
        when(projects.selectOwned(7L,9L)).thenAnswer(i->project);
        service.createProject(dto(),9L);
        assertNotEquals(first,workspace.getWorkspaceName());assertTrue(workspace.getWorkspaceName().startsWith("u9-"));
    }
    @Test void retryKeepsWorkspaceAndRequestId() {
        workspace=new AgentWorkspacePO();workspace.setId(4L);workspace.setParentName("allowed");workspace.setWorkspaceName("u3-fixed");
        project=new ProjectPO();project.setId(7L);project.setUserId(3L);project.setDeviceId(2L);project.setWorkspaceId(4L);
        project.setStatus("ACTIVE");project.setWorkspaceStatus("FAILED");
        when(devices.selectWorkspaceRoot(2L,"allowed")).thenReturn(new AgentWorkspaceRootPO());
        when(devices.retryProjectWorkspace(4L,2L)).thenReturn(1);
        service.retryPreparation(7L,3L);
        ArgumentCaptor<AgentCommand> command=ArgumentCaptor.forClass(AgentCommand.class);
        verify(gateway).send(eq("device-2"),command.capture());
        assertEquals("4",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(command.getValue().getPayload()).path("requestId").asText());
        verify(devices,never()).insertCreatingWorkspace(any());verify(projects,never()).insert(any());
    }
    @Test void sendFailurePreservesProjectAndMarksDirectoryFailed() {
        doThrow(new BusinessException(ErrorCode.AGENT_OFFLINE)).when(gateway).send(any(),any());
        var result=service.createProject(dto(),3L);
        assertEquals(7L,result.getId());
        var failed=ArgumentCaptor.forClass(AgentWorkspacePO.class);verify(devices).failWorkspace(failed.capture());
        assertEquals(4L,failed.getValue().getId());assertEquals("PREPARATION_DISPATCH_FAILED",failed.getValue().getFailureCode());
    }
    @Test void anotherUsersProjectIsNotVisible() {
        var failure=assertThrows(BusinessException.class,()->service.getProject(7L,9L));
        assertEquals(ErrorCode.NOT_FOUND,failure.getErrorCode());verify(gateway,never()).send(any(),any());
    }
    @Test void pendingRetryDoesNotSendDuplicateCommand() {
        service.createProject(dto(),3L);clearInvocations(gateway);
        service.retryPreparation(7L,3L);verify(gateway,never()).send(any(),any());
    }
}
