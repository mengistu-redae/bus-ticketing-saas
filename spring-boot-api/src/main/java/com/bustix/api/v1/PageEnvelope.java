package com.bustix.api.v1;

import java.util.List;

/**
 * The list-response shape for every {@code /v1} collection endpoint - a
 * stable, self-describing envelope rather than a bare array plus an
 * {@code X-Total-Count} header (the pattern the internal API uses). A partner
 * paginates off the {@code total} field without reading headers.
 *
 * v1 slices in memory after the full tenant-scoped result set is assembled,
 * same limitation the internal search endpoints have - the honest fix is a
 * DB-paginated query, tracked for later.
 */
public record PageEnvelope<T>(
    List<T> items,
    int page,
    int size,
    long total
) {

    /** page clamped to >= 0, size to [1, 100]. */
    public static <T> PageEnvelope<T> of(List<T> all, int page, int size) {
        int pageNumber = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);
        List<T> slice = all.stream()
                .skip((long) pageNumber * pageSize)
                .limit(pageSize)
                .toList();
        return new PageEnvelope<>(slice, pageNumber, pageSize, all.size());
    }
}
