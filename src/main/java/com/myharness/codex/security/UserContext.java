package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;

public final class UserContext {

    private static final ThreadLocal<UserPrincipal> CURRENT_USER = new ThreadLocal<UserPrincipal>();

    private UserContext() {
    }

    public static void set(UserPrincipal principal) {
        CURRENT_USER.set(principal);
    }

    public static UserPrincipal requireCurrentUser() {
        UserPrincipal principal = CURRENT_USER.get();
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return principal;
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
