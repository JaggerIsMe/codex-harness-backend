package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.CreateProjectDTO;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.AgentWorkspacePO;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.entity.vo.ProjectVO;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectServiceImplTest {
    @Test void createsProjectWithAnExclusiveWorkspaceBinding() {
        ProjectMapper projects=mock(ProjectMapper.class);
        AgentDeviceMapper devices=mock(AgentDeviceMapper.class);
        AgentDevicePO device=new AgentDevicePO(); device.setId(2L); device.setStatus("ONLINE"); device.setIsolationMode("WINDOWS_ELEVATED");
        AgentWorkspacePO workspace=new AgentWorkspacePO(); workspace.setId(4L); workspace.setDeviceId(2L);
        workspace.setStatus("ENABLED"); workspace.setRootPath("D:/projects/order-service");
        when(devices.selectById(2L)).thenReturn(device);
        when(devices.selectWorkspace(4L,2L)).thenReturn(workspace);
        doAnswer(invocation -> { ((ProjectPO) invocation.getArgument(0)).setId(7L); return 1; }).when(projects).insert(any());
        ProjectPO stored=new ProjectPO(); stored.setId(7L); stored.setUserId(3L); stored.setDeviceId(2L); stored.setWorkspaceId(4L);
        stored.setProjectName("订单服务"); stored.setStatus("ACTIVE"); stored.setIsolationMode("WINDOWS_ELEVATED");
        when(projects.selectOwned(7L,3L)).thenReturn(stored);
        CreateProjectDTO dto=new CreateProjectDTO(); dto.setProjectName(" 订单服务 "); dto.setDeviceId(2L); dto.setWorkspaceId(4L);

        ProjectVO result=new ProjectServiceImpl(projects,devices).createProject(dto,3L);

        assertEquals(7L,result.getId());
        assertEquals("订单服务",result.getProjectName());
        assertEquals("WINDOWS_ELEVATED",result.getIsolationMode());
    }
}
