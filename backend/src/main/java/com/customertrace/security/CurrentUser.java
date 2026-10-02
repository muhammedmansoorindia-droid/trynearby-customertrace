package com.customertrace.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The authenticated principal carried on the security context.
 *
 * <p>Holds the numeric id and the {@code user_id} string plus the role authorities taken from
 * the verified token. Services use {@link #getUserId()} to scope queries, so ownership is
 * decided from the authenticated identity rather than anything the client supplied.
 */
public record CurrentUser(Long userId, String userIdName, List<GrantedAuthority> authorities) {

    public CurrentUser(Long userId, String userIdName, Collection<GrantedAuthority> authorities) {
        this(userId, userIdName, List.copyOf(authorities));
    }

    public boolean hasRole(String roleCode) {
        String expected = "ROLE_" + roleCode.toUpperCase();
        return authorities.stream().anyMatch(a -> expected.equals(a.getAuthority()));
    }

    public boolean isAdmin() {
        return hasRole(RoleCodes.ADMIN);
    }

    public boolean isRestaurant() {
        return hasRole(RoleCodes.RESTAURANT);
    }

    public boolean isCreator() {
        return hasRole(RoleCodes.CREATOR);
    }
}