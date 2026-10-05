package com.cafepos.security;

import com.cafepos.platform.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {

    private CurrentUser() {}

    public static AuthUser get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof AuthUser u)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Not logged in");
        }
        return u;
    }

    public static long id() {
        return get().id();
    }

    public static boolean has(String permission) {
        return get().has(permission);
    }

    public static void require(String permission) {
        if (!has(permission)) {
            throw ApiException.forbidden("You do not have permission: " + permission);
        }
    }
}
