package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AgentDeviceMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DeviceAuthenticationService {
    private final AgentDeviceMapper deviceMapper;

    public DeviceAuthenticationService(AgentDeviceMapper deviceMapper) {
        this.deviceMapper = deviceMapper;
    }

    public AgentDevicePO authenticate(String deviceCode, String authorization) {
        if (!StringUtils.hasText(deviceCode) || !StringUtils.hasText(authorization)
                || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.AGENT_UNAUTHORIZED);
        }
        String token = authorization.substring(7).trim();
        AgentDevicePO device = deviceMapper.selectByCode(deviceCode.trim());
        if (device == null || !SecureDigests.matches(token, device.getTokenHash())) {
            throw new BusinessException(ErrorCode.AGENT_UNAUTHORIZED);
        }
        if ("DISABLED".equals(device.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_DISABLED);
        }
        return device;
    }
}
