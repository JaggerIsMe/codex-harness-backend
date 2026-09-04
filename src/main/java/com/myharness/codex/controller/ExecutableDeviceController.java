package com.myharness.codex.controller;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.service.ExecutableDeviceService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
public class ExecutableDeviceController {
    private final ExecutableDeviceService service;
    public ExecutableDeviceController(ExecutableDeviceService service){this.service=service;}
    @GetMapping("/api/v1/devices/available") public ApiResponseVO<List<ExecutableDeviceVO>> list(){return ApiResponseVO.success(service.list());}
}

