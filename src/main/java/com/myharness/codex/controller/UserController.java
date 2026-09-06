package com.myharness.codex.controller;
import com.myharness.codex.entity.dto.*;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.service.UserManagementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/v1")
public class UserController {
    private final UserManagementService service;
    public UserController(UserManagementService service) {this.service=service;}
    @GetMapping("/users") public ApiResponseVO<PageVO<ManagedUserVO>> list(@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponseVO.success(service.list(keyword,status,page,size));
    }
    @PostMapping("/users") public ApiResponseVO<ManagedUserVO> create(@Valid @RequestBody CreateUserDTO dto) {return ApiResponseVO.success(service.create(dto));}
    @PutMapping("/users/{id}") public ApiResponseVO<ManagedUserVO> update(@PathVariable Long id,@Valid @RequestBody UpdateUserDTO dto) {return ApiResponseVO.success(service.update(id,dto));}
    @PutMapping("/users/{id}/roles") public ApiResponseVO<ManagedUserVO> role(@PathVariable Long id,@Valid @RequestBody AssignRoleDTO dto) {return ApiResponseVO.success(service.role(id,dto));}
    @PutMapping("/users/{id}/devices") public ApiResponseVO<ManagedUserVO> devices(@PathVariable Long id,@Valid @RequestBody AssignDevicesDTO dto) {return ApiResponseVO.success(service.assignDevices(id,dto));}
    @PutMapping("/users/{id}/experts") public ApiResponseVO<ManagedUserVO> experts(@PathVariable Long id,@Valid @RequestBody AssignExpertsDTO dto) {return ApiResponseVO.success(service.assignExperts(id,dto));}
    @PostMapping("/users/{id}/reset-password") public ApiResponseVO<Void> reset(@PathVariable Long id,@Valid @RequestBody ResetPasswordDTO dto) {service.resetPassword(id,dto);return ApiResponseVO.success(null);}
    @GetMapping("/roles") public ApiResponseVO<List<RoleVO>> roles() {return ApiResponseVO.success(service.roles());}
}

