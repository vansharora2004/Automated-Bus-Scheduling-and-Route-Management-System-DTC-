package com.dtc.transit.common.paging;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;

/**
 * The shape every paginated list endpoint returns.
 *
 * <p>One response type for all of them means a client learns the envelope once. The alternative, each
 * controller returning Spring's {@code Page}, leaks internal field names and changes shape between
 * releases.
 *
 * @param totalElements null when the count query was skipped with {@code includeTotal=false}
 * @param totalPages    null for the same reason
 * @param sort          the sort actually applied, including the id tiebreaker, so a caller can see
 *     what produced the ordering rather than having to guess
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        Long totalElements,
        Integer totalPages,
        boolean hasNext,
        List<String> sort) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.map(mapper).getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                describe(page.getSort()));
    }

    /**
     * Builds a response from a slice, which is what {@code includeTotal=false} produces.
     *
     * <p>Totals are null rather than zero. Zero would be a lie: the count was never run.
     */
    public static <E, T> PageResponse<T> of(Slice<E> slice, Function<E, T> mapper) {
        return new PageResponse<>(
                slice.map(mapper).getContent(),
                slice.getNumber(),
                slice.getSize(),
                null,
                null,
                slice.hasNext(),
                describe(slice.getSort()));
    }

    private static List<String> describe(Sort sort) {
        return sort.stream()
                .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                .toList();
    }
}
