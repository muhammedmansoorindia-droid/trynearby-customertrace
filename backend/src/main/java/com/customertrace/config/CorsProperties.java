package com.customertrace.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * CORS allow-list, bound from {@code customer-trace.cors.allowed-origins}.
 *
 * <p>Origins are explicit and comma-separated. A wildcard is rejected at startup because it
 * cannot be combined with credentialed requests and would defeat the CSRF/cookie design.
 */
@Configuration
@ConfigurationProperties(prefix = "customer-trace.cors")
public class CorsProperties {

    private String allowedOrigins = "http://localhost:5173";

    public List<String> getOriginList() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }
}