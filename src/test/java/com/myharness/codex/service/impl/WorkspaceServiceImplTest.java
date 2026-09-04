package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.CreateWorkspaceDTO;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.AgentWorkspacePO;
import com.myharness.codex.entity.po.AgentWorkspaceRootPO;
import com.myharness.codex.gateway.AgentCommand;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.AgentDeviceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceImplTest {
    @Mock private AgentDeviceMapper mapper;
    @Mock private AgentCommandGateway gateway;
    @Mock private TransactionTemplate transactions;
    private WorkspaceServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0))
                        .doInTransaction(mock(TransactionStatus.class)));
        service = new WorkspaceServiceImpl(mapper, gateway, transactions);
    }

    @Test
    void createsPendingWorkspaceAndSendsLogicalParentOnly() {
        AgentDevicePO device = new AgentDevicePO();
        device.setId(3L); device.setDeviceCode("device-3"); device.setStatus("ONLINE");
        AgentWorkspaceRootPO root = new AgentWorkspaceRootPO();
        root.setId(7L); root.setDeviceId(3L); root.setRootName("development"); root.setStatus("ENABLED");
        when(mapper.selectById(3L)).thenReturn(device);
        when(gateway.isOnline("device-3")).thenReturn(true);
        when(mapper.selectWorkspaceRoot(3L, "development")).thenReturn(root);
        doAnswer(invocation -> {
            AgentWorkspacePO value = invocation.getArgument(0);
            value.setId(11L);
            return 1;
        }).when(mapper).insertCreatingWorkspace(any());
        AgentWorkspacePO stored = new AgentWorkspacePO();
        stored.setId(11L); stored.setDeviceId(3L); stored.setWorkspaceName("order-service");
        stored.setParentName("development"); stored.setProjectType("empty"); stored.setStatus("CREATING");
        when(mapper.selectWorkspace(11L, 3L)).thenReturn(stored);

        CreateWorkspaceDTO dto = new CreateWorkspaceDTO();
        dto.setParentName("development"); dto.setWorkspaceName("order-service"); dto.setProjectType("empty");
        service.create(3L, dto, 5L);

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(gateway).send(eq("device-3"), command.capture());
        assertEquals("CREATE_WORKSPACE", command.getValue().getType());
        assertEquals("11", command.getValue().getCorrelationId());
        Map<?, ?> payload = (Map<?, ?>) command.getValue().getPayload();
        assertEquals("development", payload.get("parentName"));
        assertEquals("order-service", payload.get("workspaceName"));
    }
}
