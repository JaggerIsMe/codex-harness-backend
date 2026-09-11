package com.myharness.codex.security;

import java.util.Set;

/** Shared exact public routes; profile, logout and account mutations remain authenticated. */
public final class PublicAuthEndpoints {
    private static final Set<String> POST_PATHS = Set.of(
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/activation/validate", "/api/v1/auth/activate",
            "/api/v1/auth/activation/resend", "/api/v1/auth/password-reset/code", "/api/v1/auth/password-reset");
    private PublicAuthEndpoints() {}
    public static String[] postPaths() { return POST_PATHS.toArray(String[]::new); }
    public static boolean allows(String method, String path) { return "POST".equals(method) && POST_PATHS.contains(path); }
}
