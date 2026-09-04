package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.CreateEnrollmentDTO;
import com.myharness.codex.entity.dto.DeviceStatusDTO;
import com.myharness.codex.entity.dto.CreateWorkspaceDTO;
import com.myharness.codex.entity.vo.AgentDeviceVO;
import com.myharness.codex.entity.vo.AgentWorkspaceVO;
import com.myharness.codex.entity.vo.AgentWorkspaceRootVO;
import com.myharness.codex.entity.vo.ApiResponseVO;
import com.myharness.codex.entity.vo.EnrollmentVO;
import com.myharness.codex.mapper.AgentDeviceMapper;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.AgentEnrollmentService;
import com.myharness.codex.service.AgentDeviceService;
import com.myharness.codex.service.WorkspaceService;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {
    private final AgentEnrollmentService enrollmentService;
    private final AgentDeviceMapper deviceMapper;
    private final AgentDeviceService deviceService;
    private final WorkspaceService workspaceService;

    public DeviceController(AgentEnrollmentService enrollmentService, AgentDeviceMapper deviceMapper,
                            AgentDeviceService deviceService, WorkspaceService workspaceService) {
        this.enrollmentService = enrollmentService;
        this.deviceMapper = deviceMapper;
        this.deviceService = deviceService;
        this.workspaceService = workspaceService;
    }

    @PostMapping("/enrollments")
    public ApiResponseVO<EnrollmentVO> createEnrollment(@Valid @RequestBody(required=false) CreateEnrollmentDTO dto) {
        return ApiResponseVO.success(enrollmentService.create(dto, UserContext.requireCurrentUser().getId()));
    }

    @GetMapping
    public ApiResponseVO<List<AgentDeviceVO>> devices() {
        return ApiResponseVO.success(deviceMapper.selectAll().stream().map(AgentDeviceVO::new).collect(Collectors.toList()));
    }

    @GetMapping("/{deviceId}/workspaces")
    public ApiResponseVO<List<AgentWorkspaceVO>> workspaces(@PathVariable Long deviceId) {
        return ApiResponseVO.success(workspaceService.list(deviceId));
    }

    @GetMapping("/{deviceId}/workspace-roots")
    public ApiResponseVO<List<AgentWorkspaceRootVO>> workspaceRoots(@PathVariable Long deviceId) {
        return ApiResponseVO.success(workspaceService.listRoots(deviceId));
    }

    @PostMapping("/{deviceId}/workspaces")
    public ApiResponseVO<AgentWorkspaceVO> createWorkspace(@PathVariable Long deviceId,
                                                           @Valid @RequestBody CreateWorkspaceDTO dto) {
        return ApiResponseVO.success("工作区创建请求已提交",
                workspaceService.create(deviceId, dto, UserContext.requireCurrentUser().getId()));
    }

    @PatchMapping("/{deviceId}/status")
    public ApiResponseVO<AgentDeviceVO> status(@PathVariable Long deviceId,@Valid @RequestBody DeviceStatusDTO dto) {
        return ApiResponseVO.success(deviceService.changeStatus(deviceId,dto.getStatus()));
    }
}
