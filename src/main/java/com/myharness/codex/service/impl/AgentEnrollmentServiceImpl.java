package com.myharness.codex.service.impl;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.CreateEnrollmentDTO;
import com.myharness.codex.entity.dto.EnrollmentRequestDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.AgentEnrollmentPO;
import com.myharness.codex.entity.vo.EnrollmentResultVO;
import com.myharness.codex.entity.vo.EnrollmentVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.AgentEnrollmentMapper;
import com.myharness.codex.security.OpaqueTokenGenerator;
import com.myharness.codex.security.SecureDigests;
import com.myharness.codex.service.AgentEnrollmentService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AgentEnrollmentServiceImpl implements AgentEnrollmentService {
    private final AgentEnrollmentMapper enrollmentMapper;
    private final AgentDeviceMapper deviceMapper;
    private final OpaqueTokenGenerator tokenGenerator;
    private final AgentProperties properties;

    public AgentEnrollmentServiceImpl(AgentEnrollmentMapper enrollmentMapper, AgentDeviceMapper deviceMapper,
                                      OpaqueTokenGenerator tokenGenerator, AgentProperties properties) {
        this.enrollmentMapper = enrollmentMapper;
        this.deviceMapper = deviceMapper;
        this.tokenGenerator = tokenGenerator;
        this.properties = properties;
    }

    @Override
    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAuthority('device:manage')")
    public EnrollmentVO create(CreateEnrollmentDTO dto, Long operatorId) {
        long minutes = dto != null && dto.getExpiresInMinutes() != null
                ? dto.getExpiresInMinutes() : properties.getEnrollmentTtlMinutes();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(minutes);
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = tokenGenerator.enrollmentCode();
            AgentEnrollmentPO enrollment = new AgentEnrollmentPO();
            enrollment.setEnrollmentCodeHash(SecureDigests.sha256(code));
            enrollment.setStatus("PENDING");
            enrollment.setExpiresAt(expiresAt);
            enrollment.setCreatedBy(operatorId);
            try {
                enrollmentMapper.insert(enrollment);
                return new EnrollmentVO(code, expiresAt);
            } catch (DuplicateKeyException ignored) {
                // Generate another high-entropy one-time code on the extremely unlikely collision.
            }
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法生成设备注册码");
    }

    @Override
    @Transactional
    public EnrollmentResultVO enroll(EnrollmentRequestDTO dto) {
        String codeHash = SecureDigests.sha256(dto.getEnrollmentCode().trim());
        AgentEnrollmentPO enrollment = enrollmentMapper.selectByHashForUpdate(codeHash);
        LocalDateTime now = LocalDateTime.now();
        if (enrollment == null || !"PENDING".equals(enrollment.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_ENROLLMENT_INVALID);
        }
        if (!enrollment.getExpiresAt().isAfter(now)) {
            enrollmentMapper.markExpired(enrollment.getId());
            throw new BusinessException(ErrorCode.AGENT_ENROLLMENT_INVALID);
        }

        String token = tokenGenerator.deviceToken();
        AgentDevicePO device = new AgentDevicePO();
        device.setEnrollmentId(enrollment.getId());
        device.setDeviceCode(tokenGenerator.deviceCode());
        device.setDeviceName(dto.getDeviceName().trim());
        device.setTokenHash(SecureDigests.sha256(token));
        device.setStatus("PENDING");
        device.setAgentVersion(dto.getAgentVersion().trim());
        device.setOsName(dto.getOsName().trim());
        device.setOsVersion(trimToNull(dto.getOsVersion()));
        if (enrollmentMapper.markUsed(enrollment.getId(), now) != 1) {
            throw new BusinessException(ErrorCode.AGENT_ENROLLMENT_CONFLICT);
        }
        try {
            deviceMapper.insert(device);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.AGENT_ENROLLMENT_CONFLICT);
        }
        return new EnrollmentResultVO(device.getDeviceCode(), token);
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
