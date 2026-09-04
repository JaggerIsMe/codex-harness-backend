package com.myharness.codex.service.impl;

import com.myharness.codex.config.AgentProperties;
import com.myharness.codex.entity.dto.EnrollmentRequestDTO;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.po.AgentEnrollmentPO;
import com.myharness.codex.entity.vo.EnrollmentResultVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.mapper.AgentEnrollmentMapper;
import com.myharness.codex.security.OpaqueTokenGenerator;
import com.myharness.codex.security.SecureDigests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentEnrollmentServiceImplTest {
    @Mock private AgentEnrollmentMapper enrollmentMapper;
    @Mock private AgentDeviceMapper deviceMapper;
    private AgentEnrollmentServiceImpl service;

    @BeforeEach void setUp() {
        service=new AgentEnrollmentServiceImpl(enrollmentMapper,deviceMapper,new OpaqueTokenGenerator(),new AgentProperties());
    }

    @Test void consumesEnrollmentAndStoresOnlyTokenHash() {
        AgentEnrollmentPO enrollment=new AgentEnrollmentPO(); enrollment.setId(9L); enrollment.setStatus("PENDING");
        enrollment.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(enrollmentMapper.selectByHashForUpdate(SecureDigests.sha256("ENR-ABCD-EFGH"))).thenReturn(enrollment);
        when(enrollmentMapper.markUsed(eq(9L),any())).thenReturn(1);
        EnrollmentResultVO result=service.enroll(request());
        ArgumentCaptor<AgentDevicePO> captor=ArgumentCaptor.forClass(AgentDevicePO.class);
        verify(deviceMapper).insert(captor.capture());
        assertTrue(result.getDeviceToken().startsWith("hdt_"));
        assertNotEquals(result.getDeviceToken(),captor.getValue().getTokenHash());
        assertTrue(SecureDigests.matches(result.getDeviceToken(),captor.getValue().getTokenHash()));
        assertEquals("PENDING",captor.getValue().getStatus());
    }

    @Test void rejectsAndExpiresAnExpiredEnrollment() {
        AgentEnrollmentPO enrollment=new AgentEnrollmentPO(); enrollment.setId(9L); enrollment.setStatus("PENDING");
        enrollment.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(enrollmentMapper.selectByHashForUpdate(any())).thenReturn(enrollment);
        BusinessException exception=assertThrows(BusinessException.class,()->service.enroll(request()));
        assertEquals(ErrorCode.AGENT_ENROLLMENT_INVALID,exception.getErrorCode());
        verify(enrollmentMapper).markExpired(9L); verifyNoInteractions(deviceMapper);
    }

    private EnrollmentRequestDTO request() {
        EnrollmentRequestDTO dto=new EnrollmentRequestDTO(); dto.setEnrollmentCode("ENR-ABCD-EFGH");dto.setDeviceName("dev-pc");
        dto.setAgentVersion("1.0.0");dto.setOsName("Windows 11");dto.setOsVersion("10.0");return dto;
    }
}
