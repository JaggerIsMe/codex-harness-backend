package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.McpConfigurationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/mcp-configurations")
public class McpConfigurationController {
    private final McpConfigurationService service;
    public McpConfigurationController(McpConfigurationService service){this.service=service;}
    private Long user(){return UserContext.requireCurrentUser().getId();}
    @GetMapping public ApiResponseVO<List<McpConfigurationVO>> list(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String status){return ApiResponseVO.success(service.list(keyword,status,user()));}
    @PostMapping public ApiResponseVO<McpConfigurationVO> create(@Valid @RequestBody McpConfigurationDTO input){return ApiResponseVO.success("MCP 配置创建成功",service.create(input,user()));}
    @PutMapping("/{id}") public ApiResponseVO<McpConfigurationVO> update(@PathVariable Long id,@Valid @RequestBody McpConfigurationDTO input){return ApiResponseVO.success("MCP 配置新版本已创建",service.update(id,input,user()));}
    @PutMapping("/{id}/status") public ApiResponseVO<McpConfigurationVO> status(@PathVariable Long id,@Valid @RequestBody McpStatusDTO input){return ApiResponseVO.success(service.status(id,input,user()));}
    @GetMapping("/{id}/versions") public ApiResponseVO<List<McpConfigurationVersionVO>> versions(@PathVariable Long id){return ApiResponseVO.success(service.versions(id,user()));}
    @PutMapping("/{id}/versions/{versionId}/revoke") public ApiResponseVO<McpConfigurationVersionVO> revoke(@PathVariable Long id,@PathVariable Long versionId){return ApiResponseVO.success("MCP 配置版本已撤销",service.revoke(id,versionId,user()));}
    @GetMapping("/selectable-versions") public ApiResponseVO<List<McpSelectableVersionVO>> selectableVersions(){return ApiResponseVO.success(service.selectableVersions(user()));}
}
