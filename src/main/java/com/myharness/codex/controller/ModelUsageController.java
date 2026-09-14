package com.myharness.codex.controller;
import com.myharness.codex.entity.dto.UsageDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.ModelUsageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
public class ModelUsageController {
    private final ModelUsageService service;
    public ModelUsageController(ModelUsageService service){this.service=service;}
    private Long actor(){return UserContext.requireCurrentUser().getId();}
    @GetMapping("/api/v1/usage/prices") public ApiResponseVO<List<UsageVO.Price>> prices(){return ApiResponseVO.success(service.prices(actor()));}
    @PostMapping("/api/v1/usage/prices") public ApiResponseVO<UsageVO.Price> price(@Valid @RequestBody UsageDTO.Price dto){return ApiResponseVO.success(service.savePrice(dto,actor()));}
    @GetMapping("/api/v1/usage/users/{uid}/policy") public ApiResponseVO<UsageVO.Policy> policy(@PathVariable Long uid){return ApiResponseVO.success(service.policy(uid,actor()));}
    @PutMapping("/api/v1/usage/users/{uid}/policy") public ApiResponseVO<UsageVO.Policy> policy(@PathVariable Long uid,@Valid @RequestBody UsageDTO.Policy dto){return ApiResponseVO.success(service.savePolicy(uid,dto,actor()));}
    @GetMapping("/api/v1/usage/summary") public ApiResponseVO<UsageVO.Summary> summary(@RequestParam(required=false) Long userId){return ApiResponseVO.success(service.summary(userId==null?actor():userId,actor()));}
    @GetMapping("/api/v1/usage/records") public ApiResponseVO<UsageVO.Page> records(
        @RequestParam(defaultValue="false") boolean allUsers,@RequestParam(required=false) Long userId,
        @RequestParam(required=false) Long modelVersionId,@RequestParam(required=false) Long turnId,@RequestParam(required=false) Long projectId,
        @RequestParam(required=false) LocalDate start,@RequestParam(required=false) LocalDate end,
        @RequestParam(defaultValue="") String state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="30") int pageSize) {
        return ApiResponseVO.success(service.records(userId!=null?userId:allUsers?null:actor(),modelVersionId,turnId,projectId,start,end,state,page,pageSize,actor()));
    }
    @PostMapping("/api/v1/usage/records/{id}/resolve") public ApiResponseVO<Void> resolve(@PathVariable String id,@Valid @RequestBody UsageDTO.Resolve dto){service.resolve(id,dto,actor());return ApiResponseVO.success(null);}
}
