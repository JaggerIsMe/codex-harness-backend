package com.myharness.codex.service.impl;

import com.myharness.codex.entity.dto.CreateWorkspaceDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.AgentWorkspacePO;
import com.myharness.codex.entity.po.AgentWorkspaceRootPO;
import com.myharness.codex.entity.vo.AgentWorkspaceRootVO;
import com.myharness.codex.entity.vo.AgentWorkspaceVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommand;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.service.WorkspaceService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WorkspaceServiceImpl implements WorkspaceService {
    private final AgentDeviceMapper mapper;
    private final AgentCommandGateway gateway;
    private final TransactionTemplate transactions;

    public WorkspaceServiceImpl(AgentDeviceMapper mapper, AgentCommandGateway gateway,
                                TransactionTemplate transactions) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.transactions = transactions;
    }

    @Override
    public List<AgentWorkspaceVO> list(Long deviceId) {
        requireDevice(deviceId);
        return mapper.selectWorkspaces(deviceId).stream().map(AgentWorkspaceVO::new).collect(Collectors.toList());
    }

    @Override
    public List<AgentWorkspaceRootVO> listRoots(Long deviceId) {
        requireDevice(deviceId);
        return mapper.selectWorkspaceRoots(deviceId).stream().map(AgentWorkspaceRootVO::new).collect(Collectors.toList());
    }

    @Override
    public AgentWorkspaceVO create(Long deviceId, CreateWorkspaceDTO dto, Long operatorId) {
        AgentDevicePO device = requireOnlineDevice(deviceId);
        String parentName = dto.getParentName().trim();
        String workspaceName = dto.getWorkspaceName().trim();
        String projectType = dto.getProjectType() == null || dto.getProjectType().trim().isEmpty()
                ? "empty" : dto.getProjectType().trim();
        AgentWorkspaceRootPO root = mapper.selectWorkspaceRoot(deviceId, parentName);
        if (root == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Agent 未授权该工作区父目录");
        }

        AgentWorkspacePO existing = mapper.selectWorkspaceByName(deviceId, workspaceName);
        final AgentWorkspacePO workspace;
        if (existing != null) {
            if (!("CREATING".equals(existing.getStatus()) || "FAILED".equals(existing.getStatus()))
                    || !root.getRootName().equalsIgnoreCase(existing.getParentName())
                    || !projectType.equals(existing.getProjectType())) {
                throw new BusinessException(ErrorCode.CONFLICT, "该设备下已存在同名工作区");
            }
            existing.setCreatedBy(operatorId);
            if (mapper.retryWorkspace(existing) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "工作区状态已发生变化，请刷新后重试");
            }
            workspace = existing;
        } else try {
            workspace = transactions.execute(status -> {
                AgentWorkspacePO value = new AgentWorkspacePO();
                value.setDeviceId(deviceId);
                value.setWorkspaceName(workspaceName);
                value.setParentName(root.getRootName());
                value.setProjectType(projectType);
                value.setStatus("CREATING");
                value.setCreatedBy(operatorId);
                value.setLastReportedAt(LocalDateTime.now());
                mapper.insertCreatingWorkspace(value);
                return value;
            });
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "该设备下已存在同名工作区");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", String.valueOf(workspace.getId()));
        payload.put("parentName", root.getRootName());
        payload.put("workspaceName", workspaceName);
        payload.put("projectType", projectType);
        try {
            gateway.send(device.getDeviceCode(), new AgentCommand(
                    "CREATE_WORKSPACE", String.valueOf(workspace.getId()), payload));
        } catch (RuntimeException exception) {
            AgentWorkspacePO failed = new AgentWorkspacePO();
            failed.setId(workspace.getId());
            failed.setDeviceId(deviceId);
            failed.setFailureCode("AGENT_OFFLINE");
            failed.setFailureMessage("Workspace command could not be delivered");
            mapper.failWorkspace(failed);
            throw exception;
        }
        return new AgentWorkspaceVO(mapper.selectWorkspace(workspace.getId(), deviceId));
    }

    private AgentDevicePO requireOnlineDevice(Long deviceId) {
        AgentDevicePO device = requireDevice(deviceId);
        if ("DISABLED".equals(device.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "设备不存在或已禁用");
        }
        if (!gateway.isOnline(device.getDeviceCode())) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        return device;
    }

    private AgentDevicePO requireDevice(Long deviceId) {
        AgentDevicePO device = mapper.selectById(deviceId);
        if (device == null) throw new BusinessException(ErrorCode.NOT_FOUND, "设备不存在");
        return device;
    }
}
