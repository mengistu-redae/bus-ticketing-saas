package com.bustix.api.v1.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * HMAC-SHA256 over {@code <timestamp>.<body>}, Stripe-style. The partner
 * verifies {@code X-Bustix-Signature} against its endpoint's signing secret,
 * checking {@code X-Bustix-Timestamp} for freshness to stop replays.
 */
public final class WebhookSignature {

    private WebhookSignature() {
    }

    public static String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
