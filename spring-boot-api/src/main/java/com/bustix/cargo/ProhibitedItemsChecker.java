package com.bustix.cargo;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Section 4.2's "Validation Engine ... parse cargo descriptions ... against
 * a regex-supported blacklist" - platform-wide config
 * (bustix.cargo.prohibited-items), not per-operator, same mechanism as
 * bustix.ticketing.vat-rate (see my-notes/cargo_logistics_scope_v1.md
 * decision 4). Each configured term is compiled as a case-insensitive
 * regex, so a term can be a plain word (matches as a substring) or an
 * actual regex fragment - "regex-supported" without forcing every entry in
 * the list to be one.
 */
@Component
public class ProhibitedItemsChecker {

    private final List<Pattern> patterns;

    public ProhibitedItemsChecker(CargoProperties cargoProperties) {
        this.patterns = cargoProperties.getProhibitedItems().stream()
                .filter(StringUtils::hasText)
                .map(this::compile)
                .toList();
    }

    /** @throws ProhibitedItemException if the description matches any configured term. */
    public void check(String description) {
        if (description == null) {
            return;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(description).find()) {
                throw new ProhibitedItemException(
                        "Cargo description matches a prohibited item (\"" + pattern.pattern() + "\"): " + description);
            }
        }
    }

    private Pattern compile(String term) {
        try {
            return Pattern.compile(term, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            // A malformed regex in config shouldn't crash every waybill
            // creation - fall back to matching it as a literal substring.
            return Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE);
        }
    }
}
