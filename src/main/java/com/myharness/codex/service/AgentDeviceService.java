package com.myharness.codex.service;

import com.myharness.codex.entity.vo.AgentDeviceVO;

public interface AgentDeviceService {
    AgentDeviceVO changeStatus(Long deviceId,String status);
    java.util.List<AgentDeviceVO> list();
}
