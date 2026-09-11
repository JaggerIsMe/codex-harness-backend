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
    private final RefreshCookieService cookies;
    public AccountController(UserManagementService users,ClientSocketTickets tickets,RefreshCookieService cookies) {this.users=users;this.tickets=tickets;this.cookies=cookies;}
    @PostMapping("/change-password") public ApiResponseVO<Void> password(@Valid @RequestBody ChangePasswordDTO dto,jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        var session=UserContext.requireCurrentUser().getLoginSession();
        users.changeOwnPassword(dto);
        if (session!=null) cookies.clear(request,response,session.sid());
        return ApiResponseVO.success(null);
    }
    @PostMapping("/logout") public ApiResponseVO<Void> logout(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response) {
        var session=UserContext.requireCurrentUser().getLoginSession();
        users.logout();
        if (session!=null) cookies.clear(request,response,session.sid());
        return ApiResponseVO.success(null);
    }
    @PostMapping("/socket-ticket") public ApiResponseVO<SocketTicketVO> ticket(@RequestHeader("Authorization") String header) {
        return ApiResponseVO.success(new SocketTicketVO(tickets.issue(header.substring(7).trim()),30));
    }
}

