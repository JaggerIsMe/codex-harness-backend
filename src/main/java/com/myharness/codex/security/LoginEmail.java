package com.myharness.codex.security;

import com.myharness.codex.entity.enums.ErrorCode;
import com.myharness.codex.exception.BusinessException;
import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

/** One canonical address for database uniqueness, login, and email challenges. */
public final class LoginEmail {
    private static final Pattern LOCAL = Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~.-]+");
    private LoginEmail() {}

    public static String normalize(String input) {
        if (input == null) throw invalid();
        String value = input.strip().toLowerCase(Locale.ROOT);
        int at = value.indexOf('@');
        if (at < 1 || at != value.lastIndexOf('@') || at > 64) throw invalid();
        String local = value.substring(0, at);
        if (!LOCAL.matcher(local).matches() || local.startsWith(".") || local.endsWith(".") || local.contains("..")) throw invalid();
        String domain;
        try { domain = IDN.toASCII(value.substring(at + 1), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException exception) { throw invalid(); }
        if (!domain.contains(".") || domain.startsWith(".") || domain.endsWith(".") || domain.length() > 253) throw invalid();
        String canonical = local + "@" + domain;
        if (canonical.length() > 254) throw invalid();
        return canonical;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "请输入有效的邮箱地址");
    }
}
