package com.customertrace.security;

/**
 * Role codes shared between the security layer and the services.
 *
 * <p>Kept as constants rather than importing the JPA enum so the web layer does not depend on
 * persistence types. Values must match {@code RoleCode}.
 */
public final class RoleCodes {

    /** The TryNearby platform operator. */
    public static final String ADMIN = "ADMIN";
    public static final String RESTAURANT = "RESTAURANT";
    public static final String CREATOR = "CREATOR";

    /**
     * Account-id suffixes used by the portal-selection screens.
     *
     * <p>These are a UX convention only. They are validated for a helpful error message, never
     * used to grant access: the authoritative role always comes from {@code user_roles}.
     */
    public static final String ADMIN_SUFFIX = "trynearby";
    public static final String RESTAURANT_SUFFIX = "restrnt";
    public static final String CREATOR_SUFFIX = "creator";

    private RoleCodes() {
    }

    /** Returns the expected suffix for a portal, or null when the portal is unknown. */
    public static String suffixForPortal(String portal) {
        if (portal == null) {
            return null;
        }
        return switch (portal.toLowerCase()) {
            case "admin", "trynearby" -> ADMIN_SUFFIX;
            case "restaurant" -> RESTAURANT_SUFFIX;
            case "creator" -> CREATOR_SUFFIX;
            default -> null;
        };
    }

    /** Returns the role a portal is expected to authenticate. */
    public static String roleForPortal(String portal) {
        if (portal == null) {
            return null;
        }
        return switch (portal.toLowerCase()) {
            case "admin", "trynearby" -> ADMIN;
            case "restaurant" -> RESTAURANT;
            case "creator" -> CREATOR;
            default -> null;
        };
    }
}