package com.customertrace.domain;

/** Authoritative role codes. Never inferred from a user id suffix on the server. */
public enum RoleCode {
    ADMIN,
    RESTAURANT,
    CREATOR;

    public static boolean isValid(String code) {
        for (RoleCode r : values()) {
            if (r.name().equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }
}