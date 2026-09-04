package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AgentDeviceMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceAuthenticationServiceTest {
    @Test void authenticatesHashAndNeverRequiresPlaintextStorage() {
        AgentDeviceMapper mapper=mock(AgentDeviceMapper.class); AgentDevicePO device=new AgentDevicePO();
        device.setDeviceCode("device-1");device.setTokenHash(SecureDigests.sha256("hdt_secret"));device.setStatus("OFFLINE");
        when(mapper.selectByCode("device-1")).thenReturn(device);
        DeviceAuthenticationService service=new DeviceAuthenticationService(mapper);
        assertSame(device,service.authenticate("device-1","Bearer hdt_secret"));
        BusinessException error=assertThrows(BusinessException.class,()->service.authenticate("device-1","Bearer wrong"));
        assertEquals(ErrorCode.AGENT_UNAUTHORIZED,error.getErrorCode());
    }

    @Test void rejectsDisabledDeviceAfterValidToken() {
        AgentDeviceMapper mapper=mock(AgentDeviceMapper.class); AgentDevicePO device=new AgentDevicePO();
        device.setTokenHash(SecureDigests.sha256("token"));device.setStatus("DISABLED");when(mapper.selectByCode("device-1")).thenReturn(device);
        BusinessException error=assertThrows(BusinessException.class,
                ()->new DeviceAuthenticationService(mapper).authenticate("device-1","Bearer token"));
        assertEquals(ErrorCode.AGENT_DISABLED,error.getErrorCode());
    }
}
