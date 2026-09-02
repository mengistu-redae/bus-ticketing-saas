package com.bustix.api.v1.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Binds {@code bustix.api.webhooks.*}. */
@Component
@ConfigurationProperties(prefix = "bustix.api.webhooks")
public class WebhookProperties {

    /** Attempts before a delivery is marked failed. */
    private int maxAttempts = 8;

    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);

    /**
     * Allow callback URLs that resolve to loopback / private / link-local
     * addresses. Off in production (SSRF); on for local development so a
     * partner integration can point at a listener on the dev machine.
     */
    private boolean allowPrivateUrls = false;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public boolean isAllowPrivateUrls() {
        return allowPrivateUrls;
    }

    public void setAllowPrivateUrls(boolean allowPrivateUrls) {
        this.allowPrivateUrls = allowPrivateUrls;
    }
}
