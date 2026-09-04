package com.myharness.codex.service;

import com.myharness.codex.entity.dto.LoginDTO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.entity.vo.UserProfileVO;

public interface AuthService {

    LoginVO login(LoginDTO dto);

    UserProfileVO getCurrentUser();
}
