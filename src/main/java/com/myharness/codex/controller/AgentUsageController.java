package com.myharness.codex.controller;
import com.myharness.codex.entity.dto.UsageDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.service.ModelUsageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent/turns/{tid}/usage")
public class AgentUsageController {
    private final ModelUsageService service;
    public AgentUsageController(ModelUsageService service){this.service=service;}
    @PostMapping("/reserve") public ApiResponseVO<UsageVO.Permit> reserve(@PathVariable Long tid,@Valid @RequestBody UsageDTO.Reserve dto,
        @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader("Authorization") String auth){return ApiResponseVO.success(service.reserve(tid,dto,code,auth));}
    @PostMapping("/settle") public ApiResponseVO<Void> settle(@PathVariable Long tid,@Valid @RequestBody UsageDTO.Settlement dto,
        @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader("Authorization") String auth){service.settle(tid,dto,code,auth);return ApiResponseVO.success(null);}
}
