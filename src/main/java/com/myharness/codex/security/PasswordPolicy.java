package com.myharness.codex.security;
import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import java.nio.charset.StandardCharsets;
public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static void validate(String value) {
        if(value==null || value.length()<12 || value.length()>64 || value.getBytes(StandardCharsets.UTF_8).length>72
                || value.chars().noneMatch(Character::isLetter) || value.chars().noneMatch(Character::isDigit))
            throw new BusinessException(ErrorCode.INVALID_REQUEST,"密码须为12–64字符，包含字母和数字，UTF-8长度不超过72字节");
    }
}

