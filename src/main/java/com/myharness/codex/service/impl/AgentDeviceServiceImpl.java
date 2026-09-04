package com.myharness.codex.service.impl;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.entity.po.AgentDevicePO;
import com.myharness.codex.entity.vo.AgentDeviceVO;
import com.myharness.codex.exception.BusinessException;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.service.AgentDeviceService;
import com.myharness.codex.service.AgentEventService;
import com.myharness.codex.websocket.AgentConnectionRegistry;
import org.springframework.stereotype.Service;

@Service
public class AgentDeviceServiceImpl implements AgentDeviceService {
    private final AgentDeviceMapper mapper; private final AgentConnectionRegistry connections; private final AgentEventService events;
    public AgentDeviceServiceImpl(AgentDeviceMapper mapper,AgentConnectionRegistry connections,AgentEventService events) {
        this.mapper=mapper; this.connections=connections; this.events=events;
    }
    @Override public AgentDeviceVO changeStatus(Long id,String requested) {
        AgentDevicePO device=mapper.selectById(id);
        if(device==null) throw new BusinessException(ErrorCode.NOT_FOUND,"设备不存在");
        String status=requested.trim().toUpperCase();
        if(!"ENABLED".equals(status) && !"DISABLED".equals(status)) throw new BusinessException(ErrorCode.INVALID_REQUEST,"状态只能是 ENABLED 或 DISABLED");
        if("DISABLED".equals(status)) {
            mapper.updateStatus(id,"DISABLED"); connections.disconnect(device.getDeviceCode(),"Device disabled by administrator"); events.disconnected(id);
        } else mapper.updateStatus(id,"OFFLINE");
        return new AgentDeviceVO(mapper.selectById(id));
    }
}
