package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.CreateProjectDTO;
import com.myharness.codex.entity.dto.WorkspacePageQuery;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.*;
import com.myharness.codex.entity.vo.ProjectVO;
import com.myharness.codex.entity.vo.PageVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.*;
import com.myharness.codex.mapper.*;
import com.myharness.codex.security.AuthorizationService;
import com.myharness.codex.service.ProjectService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class ProjectServiceImpl implements ProjectService {
    private final ProjectMapper projects;
    private final AgentDeviceMapper devices;
    private final AuthorizationService access;
    private final AgentCommandGateway gateway;
    private final TransactionTemplate transactions;
    public ProjectServiceImpl(ProjectMapper projects,AgentDeviceMapper devices,AuthorizationService access,
                              AgentCommandGateway gateway,TransactionTemplate transactions) {
        this.projects=projects;this.devices=devices;this.access=access;this.gateway=gateway;this.transactions=transactions;
    }
    @Override public ProjectVO createProject(CreateProjectDTO dto,Long userId) {
        access.requirePermission(userId,"project:create");access.requireDevice(userId,dto.getDeviceId());
        String requestKey=UUID.fromString(dto.getRequestKey()).toString();
        ProjectPO existing=projects.selectRequest(userId,requestKey);
        if(existing!=null) return sameRequest(existing,dto);
        AgentDevicePO device=requireReadyDevice(dto.getDeviceId());
        List<AgentWorkspaceRootPO> roots=devices.selectWorkspaceRoots(device.getId());
        if(roots.isEmpty()) throw new BusinessException(ErrorCode.CONFLICT,"机器尚未配置可创建目录的授权父目录");
        final ProjectPO project;
        try {
            project=transactions.execute(status->{
                AgentWorkspacePO workspace=new AgentWorkspacePO();
                workspace.setDeviceId(device.getId());
                workspace.setWorkspaceName("u"+userId+"-"+UUID.randomUUID().toString().replace("-",""));
                workspace.setParentName(roots.get(0).getRootName());workspace.setProjectType("empty");
                workspace.setCreatedBy(userId);workspace.setLastReportedAt(LocalDateTime.now(ZoneOffset.UTC));
                devices.insertCreatingWorkspace(workspace);
                ProjectPO value=new ProjectPO();value.setUserId(userId);value.setDeviceId(device.getId());
                value.setWorkspaceId(workspace.getId());value.setProjectName(dto.getProjectName().trim());value.setRequestKey(requestKey);
                value.setIsolationMode(device.getIsolationMode());
                projects.insert(value);return value;
            });
        } catch(DuplicateKeyException ex) {
            existing=projects.selectRequest(userId,requestKey);
            if(existing!=null) return sameRequest(existing,dto);
            throw new BusinessException(ErrorCode.CONFLICT,"已有同名项目，请更换名称");
        }
        Objects.requireNonNull(project);
        dispatch(project,userId);
        return getProject(project.getId(),userId);
    }
    @Override public ProjectVO retryPreparation(Long id,Long userId) {
        access.requirePermission(userId,"project:create");
        ProjectPO project=requireOwned(id,userId);requireReadyDevice(project.getDeviceId());
        if(!"ACTIVE".equals(project.getStatus())) throw new BusinessException(ErrorCode.CONFLICT,"项目已停用");
        if("ENABLED".equals(project.getWorkspaceStatus()) || "CREATING".equals(project.getWorkspaceStatus())) return new ProjectVO(project);
        AgentWorkspacePO workspace=devices.selectWorkspace(project.getWorkspaceId(),project.getDeviceId());
        if(workspace==null || workspace.getParentName()==null || devices.selectWorkspaceRoot(project.getDeviceId(),workspace.getParentName())==null)
            throw new BusinessException(ErrorCode.CONFLICT,"原授权父目录不可用，请联系管理员");
        if(devices.retryProjectWorkspace(project.getWorkspaceId(),project.getDeviceId())!=1)
            throw new BusinessException(ErrorCode.CONFLICT,"当前目录不可重试，请刷新状态");
        dispatch(project,userId);return getProject(id,userId);
    }
    @Override public ProjectVO getProject(Long id,Long userId) {
        access.requirePermission(userId,"project:read");return new ProjectVO(requireOwned(id,userId));
    }
    @Override public PageVO<ProjectVO> getProjects(Long userId,int page,int size,String keyword) {
        access.requirePermission(userId,"project:read");
        var query=new WorkspacePageQuery(page,size,keyword);
        long total=projects.countOwnedProjects(userId,query.keyword());
        List<ProjectVO> items=projects.selectOwnedProjects(userId,query.keyword(),query.size(),query.offset()).stream().map(ProjectVO::new).toList();
        return new PageVO<>(items,total,query.page(),query.size());
    }
    private ProjectPO requireOwned(Long id,Long userId) {
        ProjectPO value=projects.selectOwned(id,userId);
        if(value==null) throw new BusinessException(ErrorCode.NOT_FOUND,"项目不存在");
        access.requireDevice(userId,value.getDeviceId());return value;
    }
    private ProjectVO sameRequest(ProjectPO existing,CreateProjectDTO dto) {
        if(!existing.getDeviceId().equals(dto.getDeviceId()) || !existing.getProjectName().equals(dto.getProjectName().trim()))
            throw new BusinessException(ErrorCode.CONFLICT,"创建请求标识已被不同项目参数使用");
        return new ProjectVO(existing);
    }
    private AgentDevicePO requireReadyDevice(Long id) {
        AgentDevicePO device=devices.selectById(id);
        if(device==null || !"ONLINE".equals(device.getStatus()) || !gateway.isOnline(device.getDeviceCode()))
            throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        if(!"LINUX_PROJECT_PROFILE_V1".equals(device.getIsolationMode()) && !"WINDOWS_LPAC_V1".equals(device.getIsolationMode()))
            throw new BusinessException(ErrorCode.CONFLICT,"当前设备尚未通过读取隔离自检，请配置 Windows 原生隔离或 Linux 隔离 Agent");
        return device;
    }
    /** Runs only after the project + Workspace transaction committed. Send failures remain inspectable/retryable. */
    private void dispatch(ProjectPO project,Long userId) {
        AgentWorkspacePO workspace=devices.selectWorkspace(project.getWorkspaceId(),project.getDeviceId());
        try {
            access.requireDevice(userId,project.getDeviceId());
            AgentDevicePO device=requireReadyDevice(project.getDeviceId());
            Map<String,Object> payload=Map.of("requestId",String.valueOf(workspace.getId()),"parentName",workspace.getParentName(),
                    "workspaceName",workspace.getWorkspaceName(),"projectType","empty");
            gateway.send(device.getDeviceCode(),new AgentCommand("CREATE_WORKSPACE",String.valueOf(workspace.getId()),payload));
        } catch(RuntimeException ex) {
            AgentWorkspacePO failed=new AgentWorkspacePO();failed.setId(project.getWorkspaceId());failed.setDeviceId(project.getDeviceId());
            failed.setFailureCode("PREPARATION_DISPATCH_FAILED");failed.setFailureMessage("目录准备命令未送达，请检查机器状态与授权后重试");
            devices.failWorkspace(failed);
        }
    }
}
