package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ModelConfigurationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
public class ModelConfigurationController {
    private final ModelConfigurationService service; public ModelConfigurationController(ModelConfigurationService service){this.service=service;} private Long user(){return UserContext.requireCurrentUser().getId();}
    @GetMapping("/api/v1/admin/model-configurations") public ApiResponseVO<List<ModelConfigurationVO>> list(@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String status){return ApiResponseVO.success(service.list(keyword,status,user()));}
    @PostMapping("/api/v1/admin/model-configurations") public ApiResponseVO<ModelConfigurationVO> create(@Valid @RequestBody ModelConfigurationDTO input){return ApiResponseVO.success("模型配置创建成功",service.create(input,user()));}
    @PutMapping("/api/v1/admin/model-configurations/{id}") public ApiResponseVO<ModelConfigurationVO> update(@PathVariable Long id,@Valid @RequestBody ModelConfigurationDTO input){return ApiResponseVO.success("模型配置新版本已创建",service.update(id,input,user()));}
    @PutMapping("/api/v1/admin/model-configurations/{id}/status") public ApiResponseVO<ModelConfigurationVO> status(@PathVariable Long id,@Valid @RequestBody ModelStatusDTO input){return ApiResponseVO.success(service.status(id,input,user()));}
    @GetMapping("/api/v1/admin/model-configurations/{id}/versions") public ApiResponseVO<List<ModelConfigurationVersionVO>> versions(@PathVariable Long id){return ApiResponseVO.success(service.versions(id,user()));}
    @PutMapping("/api/v1/admin/model-configurations/{id}/versions/{versionId}/revoke") public ApiResponseVO<ModelConfigurationVersionVO> revoke(@PathVariable Long id,@PathVariable Long versionId){return ApiResponseVO.success("模型版本已撤销",service.revoke(id,versionId,user()));}
    @GetMapping("/api/v1/admin/model-configurations/selectable-versions") public ApiResponseVO<List<ModelSelectableVersionVO>> selectable(){return ApiResponseVO.success(service.selectableVersions(user()));}
    @GetMapping("/api/v1/devices/{deviceId}/model-assignment") public ApiResponseVO<DeviceModelAssignmentVO> assignment(@PathVariable Long deviceId){return ApiResponseVO.success(service.assignment(deviceId,user()));}
    @PutMapping("/api/v1/devices/{deviceId}/model-assignment") public ApiResponseVO<DeviceModelAssignmentVO> assign(@PathVariable Long deviceId,@Valid @RequestBody DeviceModelAssignmentDTO input){return ApiResponseVO.success("Device 模型已更新",service.assign(deviceId,input,user()));}
    @DeleteMapping("/api/v1/devices/{deviceId}/model-assignment") public ApiResponseVO<Void> unassign(@PathVariable Long deviceId,@RequestParam Long revision){service.unassign(deviceId,revision,user());return ApiResponseVO.success(null);}
}
