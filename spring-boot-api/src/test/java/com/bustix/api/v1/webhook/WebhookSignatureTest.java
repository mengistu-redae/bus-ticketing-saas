package com.bustix.api.v1.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    @Test
    void producesAStableSha256HmacOverTimestampDotBody() {
        String sig = WebhookSignature.sign("whsec_test", 1_700_000_000L, "{\"hello\":\"world\"}");

        assertThat(sig).startsWith("sha256=");
        // Deterministic for a fixed secret + timestamp + body.
        assertThat(sig).isEqualTo(WebhookSignature.sign("whsec_test", 1_700_000_000L, "{\"hello\":\"world\"}"));
    }

    @Test
    void differsWhenTheBodyOrTimestampChanges() {
        String base = WebhookSignature.sign("s", 1L, "a");
        assertThat(base).isNotEqualTo(WebhookSignature.sign("s", 1L, "b"));
        assertThat(base).isNotEqualTo(WebhookSignature.sign("s", 2L, "a"));
        assertThat(base).isNotEqualTo(WebhookSignature.sign("other", 1L, "a"));
    }
}
