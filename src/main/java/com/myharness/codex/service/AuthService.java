package com.myharness.codex.service;

import com.myharness.codex.entity.dto.LoginDTO;
import com.myharness.codex.entity.vo.LoginVO;
import com.myharness.codex.entity.vo.UserProfileVO;
import com.myharness.codex.entity.vo.SessionTokenVO;
import com.myharness.codex.entity.vo.SessionActivityVO;

public interface AuthService {

    LoginVO login(LoginDTO dto);

    SessionTokenVO refresh(String sid, String credential);

    SessionActivityVO activity();

    UserProfileVO getCurrentUser();
}
