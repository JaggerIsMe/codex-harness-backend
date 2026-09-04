package com.myharness.codex.service.impl;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.ProjectPO;
import com.myharness.codex.entity.po.SkillDeploymentPO;
import com.myharness.codex.entity.vo.SkillDeploymentVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.gateway.AgentCommand;
import com.myharness.codex.gateway.AgentCommandGateway;
import com.myharness.codex.mapper.ProjectMapper;
import com.myharness.codex.mapper.SkillMapper;
import com.myharness.codex.service.SkillDeploymentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SkillDeploymentServiceImpl implements SkillDeploymentService {
    private final SkillMapper mapper;
    private final ProjectMapper projectMapper;
    private final AgentCommandGateway gateway;
    private final AgentProperties properties;

    public SkillDeploymentServiceImpl(SkillMapper mapper, ProjectMapper projectMapper,
                                      AgentCommandGateway gateway, AgentProperties properties) {
        this.mapper = mapper; this.projectMapper = projectMapper; this.gateway = gateway; this.properties = properties;
    }

    @Override
    @Transactional
    public SkillDeploymentVO deploy(String rawScopeType, Long targetId, Long versionId, Long operatorId) {
        String scopeType = requireScopeType(rawScopeType);
        ProjectPO project = null;
        Long deviceId = targetId;
        if ("PROJECT".equals(scopeType)) {
            project = projectMapper.selectOwned(targetId, operatorId);
            if (project == null || !"ACTIVE".equals(project.getStatus()))
                throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在或已停用");
            if (!"ENABLED".equals(project.getWorkspaceStatus()) || project.getRootPath() == null)
                throw new BusinessException(ErrorCode.CONFLICT, "项目工作区未就绪");
            deviceId = project.getDeviceId();
        }
        String scopeKey = "GLOBAL".equals(scopeType) ? "GLOBAL" : "PROJECT:" + project.getId();
        SkillDeploymentPO deployment = mapper.selectDeployable(deviceId, versionId, scopeType,
                project == null ? null : project.getId(), scopeKey);
        if (deployment == null) throw new BusinessException(ErrorCode.NOT_FOUND, "执行机器或可用 Skill 版本不存在");
        if (project != null) {
            deployment.setProjectName(project.getProjectName());
            deployment.setWorkspaceName(project.getWorkspaceName());
        }
        if (!gateway.isOnline(deployment.getDeviceCode())) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        validatePublicBaseUrl();
        mapper.upsertDeployment(deployment);
        Map<String,Object> payload = payload(deployment);
        payload.put("downloadUrl", stripSlash(properties.getPublicBaseUrl()) +
                "/api/v1/agent/skill-versions/" + versionId + "/download");
        payload.put("sha256", deployment.getSha256());
        try {
            gateway.send(deployment.getDeviceCode(), new AgentCommand("INSTALL_SKILL", String.valueOf(deployment.getId()), payload));
        } catch (RuntimeException exception) {
            mapper.updateDeployment(deployment.getId(), deviceId, "FAILED", "Skill 安装命令发送失败", LocalDateTime.now());
            throw exception;
        }
        return new SkillDeploymentVO(mapper.selectDeployment(deployment.getId()));
    }

    @Override
    @Transactional
    public SkillDeploymentVO remove(Long deploymentId, Long operatorId) {
        SkillDeploymentPO deployment = mapper.selectDeployment(deploymentId);
        if (deployment == null) throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 下发记录不存在");
        requireAccessible(deployment, operatorId);
        if (!gateway.isOnline(deployment.getDeviceCode())) throw new BusinessException(ErrorCode.AGENT_OFFLINE);
        mapper.markRemoving(deploymentId, LocalDateTime.now());
        try {
            gateway.send(deployment.getDeviceCode(), new AgentCommand("REMOVE_SKILL", String.valueOf(deploymentId), payload(deployment)));
        } catch (RuntimeException exception) {
            mapper.updateDeployment(deploymentId, deployment.getDeviceId(), "FAILED", "Skill 移除命令发送失败", LocalDateTime.now());
            throw exception;
        }
        return new SkillDeploymentVO(mapper.selectDeployment(deploymentId));
    }

    @Override
    public List<SkillDeploymentVO> list(String keyword, String status, String scopeType, Long operatorId) {
        if (status != null && !status.trim().isEmpty() && !status.matches("INSTALLING|INSTALLED|REMOVING|REMOVED|FAILED"))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill 下发状态不正确");
        if (scopeType != null && !scopeType.trim().isEmpty()) requireScopeType(scopeType);
        return mapper.selectDeployments(trim(keyword), trim(status), trim(scopeType), operatorId).stream()
                .map(SkillDeploymentVO::new).collect(Collectors.toList());
    }

    private Map<String,Object> payload(SkillDeploymentPO deployment) {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("skillId", String.valueOf(deployment.getSkillId()));
        payload.put("skillName", deployment.getSkillName());
        payload.put("version", deployment.getVersion());
        payload.put("scopeType", deployment.getScopeType());
        if ("PROJECT".equals(deployment.getScopeType())) payload.put("workspaceName", deployment.getWorkspaceName());
        return payload;
    }

    private void requireAccessible(SkillDeploymentPO deployment, Long operatorId) {
        if ("PROJECT".equals(deployment.getScopeType()) && projectMapper.selectOwned(deployment.getProjectId(), operatorId) == null)
            throw new BusinessException(ErrorCode.NOT_FOUND, "Skill 下发记录不存在");
    }

    private String requireScopeType(String value) {
        String scopeType = trim(value);
        if (!("GLOBAL".equals(scopeType) || "PROJECT".equals(scopeType)))
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Skill 作用域必须是 GLOBAL 或 PROJECT");
        return scopeType;
    }

    private void validatePublicBaseUrl() {
        try {
            URI uri = URI.create(properties.getPublicBaseUrl()); String host = uri.getHost();
            boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
            if (host == null || (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback)) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "harness.agent.public-base-url 配置不安全或无效");
        }
    }
    private String stripSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    private String trim(String value) { return value == null ? null : value.trim(); }
}
