package com.cafepos.security;

import java.util.Set;

/** The logged-in person, carried in the security context. */
public record AuthUser(long id, String name, String email, String roleCode, Set<String> permissions) {
    public boolean has(String permission) {
        return permissions.contains(permission);
    }
}
