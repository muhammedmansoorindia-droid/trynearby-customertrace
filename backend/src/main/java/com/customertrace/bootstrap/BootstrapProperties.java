package com.customertrace.bootstrap;

import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Development account provisioning settings.
 *
 * <p>Seeding is <b>off by default</b> and must be enabled explicitly with
 * {@code BOOTSTRAP_ENABLED=true}. Passwords come from environment variables; there is no
 * hard-coded default, so the application cannot create a predictable account by accident.
 */
@ConfigurationProperties(prefix = "customer-trace.bootstrap")
public class BootstrapProperties {

    private boolean enabled = false;

    /**
     * Shared development password. Supplied by the operator, typically via
     * {@code DEV_ACCOUNT_PASSWORD}.
     *
     * <p>When blank, seeding is skipped entirely rather than falling back to a guessable value.
     */
    private String devAccountPassword = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDevAccountPassword() {
        return devAccountPassword;
    }

    public void setDevAccountPassword(String devAccountPassword) {
        this.devAccountPassword = devAccountPassword;
    }

    /** True when seeding may run: explicitly enabled and a password was supplied. */
    public boolean shouldSeed() {
        return enabled && devAccountPassword != null && !devAccountPassword.isBlank();
    }
}