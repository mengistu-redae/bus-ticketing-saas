package com.bustix.api.v1.webhook;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Guards webhook-URL registration against SSRF: only {@code http(s)}, and -
 * unless {@code bustix.api.webhooks.allow-private-urls} is on - not a host
 * that resolves to a loopback / private / link-local address. (DNS
 * rebinding between registration and delivery is a known residual, not
 * closed here.)
 */
@Component
public class WebhookUrlValidator {

    private final WebhookProperties properties;

    public WebhookUrlValidator(WebhookProperties properties) {
        this.properties = properties;
    }

    /** @throws IllegalArgumentException if the URL is not an acceptable webhook target. */
    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("url is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("url must be http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url must have a host");
        }
        if (properties.isAllowPrivateUrls()) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("url host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("url must not point at a private, loopback or link-local address");
            }
        }
    }
}
