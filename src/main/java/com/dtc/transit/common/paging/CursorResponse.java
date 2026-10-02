package com.dtc.transit.common.paging;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * The shape keyset-paginated list endpoints return.
 *
 * <p>Separate from {@link PageResponse} on purpose. A cursor response has no page number and no total, and
 * offering fields that cannot be populated would invite clients to depend on them. What it does offer is
 * {@code nextCursor}, which is the only safe way to ask for the following page: it is a position in the
 * ordering rather than a count of rows skipped, so rows inserted while paging cannot cause a row to be
 * repeated or missed.
 *
 * @param nextCursor pass as {@code after} to fetch the next page; null when this is the last page
 */
public record CursorResponse<T>(List<T> content, int size, Long nextCursor, boolean hasNext) {

    /**
     * Builds a response from one row more than the caller asked for.
     *
     * <p>Fetching {@code limit + 1} rows is how {@code hasNext} is answered without a second query. The extra
     * row is dropped before it is returned.
     */
    public static <E, T> CursorResponse<T> of(
            List<E> fetched, int limit, Function<E, T> mapper, ToLongFunction<E> cursorOf) {
        boolean hasNext = fetched.size() > limit;
        List<E> page = hasNext ? fetched.subList(0, limit) : fetched;
        Long nextCursor = hasNext ? cursorOf.applyAsLong(page.get(page.size() - 1)) : null;
        return new CursorResponse<>(page.stream().map(mapper).toList(), page.size(), nextCursor, hasNext);
    }
}
