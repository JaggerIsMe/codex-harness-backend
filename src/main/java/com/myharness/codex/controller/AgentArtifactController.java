package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.PublishArtifactDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.service.ConversationArtifactService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/agent/turns/{tid}/artifacts")
public class AgentArtifactController {
    private final ConversationArtifactService service;
    public AgentArtifactController(ConversationArtifactService service){this.service=service;}
    @PostMapping
    public ApiResponseVO<ConversationArtifactVO> register(@PathVariable Long tid,
            @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @Valid @RequestBody PublishArtifactDTO input) {
        return ApiResponseVO.success(service.register(tid,code,auth,input));
    }
    @GetMapping("/{aid}")
    public ApiResponseVO<ConversationArtifactVO> status(@PathVariable Long tid,@PathVariable Long aid,
            @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        return ApiResponseVO.success(service.agentStatus(tid,aid,code,auth));
    }
    @PutMapping(value="/{aid}/content",consumes="application/octet-stream")
    public ApiResponseVO<ConversationArtifactVO> upload(@PathVariable Long tid,@PathVariable Long aid,
            @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            HttpServletRequest request) throws IOException {
        return ApiResponseVO.success(service.upload(tid,aid,code,auth,request.getInputStream()));
    }
    @PostMapping("/{aid}/failed")
    public ApiResponseVO<Void> failed(@PathVariable Long tid,@PathVariable Long aid,
            @RequestHeader("X-Harness-Device-Code") String code,@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        service.agentFailed(tid,aid,code,auth); return ApiResponseVO.success(null);
    }
}
