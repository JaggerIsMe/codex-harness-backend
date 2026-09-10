package com.myharness.codex.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class LoginDTO {

    @NotBlank(message = "请输入邮箱")
    @Size(max = 254, message = "邮箱长度不能超过254个字符")
    private String email;

    @NotBlank(message = "请输入密码")
    @Size(max = 128, message = "密码长度不能超过128个字符")
    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
