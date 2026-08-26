package com.bustix.cargo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Binds bustix.cargo.* - specifically prohibited-items, a real YAML
 * sequence (see application.yml). Deliberately @ConfigurationProperties
 * rather than @Value: a plain @Value("${bustix.cargo.prohibited-items}")
 * does NOT reliably resolve a YAML list (there's no single property under
 * that exact key - Boot's relaxed binding stores it as
 * prohibited-items[0], [1], ... - only @ConfigurationProperties binds that
 * shape correctly), and this codebase has no prior precedent for a list
 * config value to follow instead.
 */
@Component
@ConfigurationProperties(prefix = "bustix.cargo")
public class CargoProperties {

    private List<String> prohibitedItems = List.of();

    public List<String> getProhibitedItems() {
        return prohibitedItems;
    }

    public void setProhibitedItems(List<String> prohibitedItems) {
        this.prohibitedItems = prohibitedItems;
    }
}
