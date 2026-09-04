package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.CreateProjectDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.AgentWorkspacePO;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.entity.vo.ProjectVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.ProjectMapper;
import com.myharness.codex.service.ProjectService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProjectServiceImpl implements ProjectService {
    private final ProjectMapper projectMapper; private final AgentDeviceMapper deviceMapper;
    public ProjectServiceImpl(ProjectMapper projectMapper,AgentDeviceMapper deviceMapper) { this.projectMapper=projectMapper; this.deviceMapper=deviceMapper; }

    @Override public ProjectVO createProject(CreateProjectDTO dto,Long operatorId) {
        AgentDevicePO device=deviceMapper.selectById(dto.getDeviceId());
        if (device==null || "DISABLED".equals(device.getStatus())) throw new BusinessException(ErrorCode.NOT_FOUND,"设备不存在或已禁用");
        if (!"ONLINE".equals(device.getStatus()) || !"WINDOWS_ELEVATED".equals(device.getIsolationMode()))
            throw new BusinessException(ErrorCode.CONFLICT,"设备未在线或未启用 Windows elevated 强隔离");
        AgentWorkspacePO workspace=deviceMapper.selectWorkspace(dto.getWorkspaceId(),device.getId());
        if (workspace==null || !"ENABLED".equals(workspace.getStatus()) || workspace.getRootPath()==null)
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"工作区不存在、未就绪或已禁用");
        ProjectPO value=new ProjectPO(); value.setUserId(operatorId); value.setDeviceId(device.getId());
        value.setWorkspaceId(workspace.getId()); value.setProjectName(dto.getProjectName().trim());
        try { projectMapper.insert(value); }
        catch (DuplicateKeyException exception) { throw new BusinessException(ErrorCode.CONFLICT,"项目名称或工作区已被其他项目占用"); }
        return new ProjectVO(projectMapper.selectOwned(value.getId(),operatorId));
    }
    @Override public ProjectVO getProject(Long projectId,Long operatorId) { return new ProjectVO(requireOwned(projectId,operatorId)); }
    @Override public List<ProjectVO> getProjects(Long operatorId) {
        return projectMapper.selectOwnedProjects(operatorId).stream().map(ProjectVO::new).collect(Collectors.toList());
    }
    private ProjectPO requireOwned(Long id,Long userId) {
        ProjectPO value=projectMapper.selectOwned(id,userId);
        if (value==null) throw new BusinessException(ErrorCode.NOT_FOUND,"项目不存在");
        return value;
    }
}
