package com.myharness.codex.service;

import com.myharness.codex.entity.dto.CreateEnrollmentDTO;
import com.myharness.codex.entity.dto.EnrollmentRequestDTO;
import com.myharness.codex.entity.vo.EnrollmentResultVO;
import com.myharness.codex.entity.vo.EnrollmentVO;

public interface AgentEnrollmentService {
    EnrollmentVO create(CreateEnrollmentDTO dto, Long operatorId);
    EnrollmentResultVO enroll(EnrollmentRequestDTO dto);
}
