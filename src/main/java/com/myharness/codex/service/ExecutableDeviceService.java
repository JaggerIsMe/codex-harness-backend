package com.myharness.codex.service;
import com.myharness.codex.entity.vo.ExecutableDeviceVO;
import com.myharness.codex.mapper.RbacMapper;
import com.myharness.codex.security.*;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public class ExecutableDeviceService {
    private final RbacMapper rbac;private final AuthorizationService access;
    public ExecutableDeviceService(RbacMapper rbac,AuthorizationService access){this.rbac=rbac;this.access=access;}
    public List<ExecutableDeviceVO> list() {
        Long id=UserContext.requireCurrentUser().getId();access.requirePermission(id,"workspace:use");return rbac.executableDevices(id);
    }
}

