package com.bustix.api.v1.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-tier request budgets for the {@code /v1} surface (WS-4). A partner's
 * tier is {@code api_clients.rate_tier}; an unknown tier falls back to
 * {@code default}. Bound from {@code bustix.api.rate-limit.*} in
 * {@code application.yml}.
 */
@Component
@ConfigurationProperties(prefix = "bustix.api.rate-limit")
public class RateLimitProperties {

    /** Master switch - off disables the filter entirely (e.g. for load tests). */
    private boolean enabled = true;

    /** tier name -> budget. Must contain a "default" entry. */
    private Map<String, Tier> tiers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Tier> getTiers() {
        return tiers;
    }

    public void setTiers(Map<String, Tier> tiers) {
        this.tiers = tiers;
    }

    /** The budget for one tier: {@code capacity} tokens, refilled {@code refillTokens} every {@code refillPeriod}. */
    public static class Tier {
        private long capacity = 120;
        private long refillTokens = 120;
        private Duration refillPeriod = Duration.ofMinutes(1);

        public long getCapacity() {
            return capacity;
        }

        public void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        public long getRefillTokens() {
            return refillTokens;
        }

        public void setRefillTokens(long refillTokens) {
            this.refillTokens = refillTokens;
        }

        public Duration getRefillPeriod() {
            return refillPeriod;
        }

        public void setRefillPeriod(Duration refillPeriod) {
            this.refillPeriod = refillPeriod;
        }
    }
}
