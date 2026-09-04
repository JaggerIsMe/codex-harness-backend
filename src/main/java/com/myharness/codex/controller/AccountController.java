package com.myharness.codex.controller;
import com.myharness.codex.entity.dto.ChangePasswordDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.*;
import com.myharness.codex.service.UserManagementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/auth")
public class AccountController {
    private final UserManagementService users;
    private final ClientSocketTickets tickets;
    public AccountController(UserManagementService users,ClientSocketTickets tickets) {this.users=users;this.tickets=tickets;}
    @PostMapping("/change-password") public ApiResponseVO<Void> password(@Valid @RequestBody ChangePasswordDTO dto) {
        users.changeOwnPassword(dto); return ApiResponseVO.success(null);
    }
    @PostMapping("/logout") public ApiResponseVO<Void> logout() { users.logout(); return ApiResponseVO.success(null); }
    @PostMapping("/socket-ticket") public ApiResponseVO<SocketTicketVO> ticket(@RequestHeader("Authorization") String header) {
        return ApiResponseVO.success(new SocketTicketVO(tickets.issue(header.substring(7).trim()),30));
    }
}

