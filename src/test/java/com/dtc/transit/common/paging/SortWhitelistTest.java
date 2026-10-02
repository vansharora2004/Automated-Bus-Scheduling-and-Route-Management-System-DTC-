package com.dtc.transit.common.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.dtc.transit.common.error.InvalidSortException;

/** Sort validation, clamping and the stable-order tiebreaker. */
class SortWhitelistTest {

    private static final Set<String> ALLOWED = Set.of("code", "name");

    private final SortWhitelist whitelist = new SortWhitelist();

    @Test
    @DisplayName("an allowed property is kept")
    void allowedPropertyPasses() {
        var result = whitelist.apply(PageRequest.of(0, 20, Sort.by("code")), ALLOWED);

        assertThat(result.getSort().getOrderFor("code")).isNotNull();
    }

    @Test
    @DisplayName("an unknown property is rejected and the error names what is allowed")
    void unknownPropertyRejected() {
        assertThatThrownBy(() -> whitelist.apply(PageRequest.of(0, 20, Sort.by("passwordHash")), ALLOWED))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("passwordHash")
                .hasMessageContaining("code")
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("one bad property among several is still rejected")
    void mixedSortRejected() {
        assertThatThrownBy(() -> whitelist.apply(PageRequest.of(0, 20, Sort.by("code", "secret")), ALLOWED))
                .isInstanceOf(InvalidSortException.class)
                .hasMessageContaining("secret");
    }

    @Test
    @DisplayName("id is appended so paging cannot repeat or skip rows")
    void idTiebreakerIsAppended() {
        var result = whitelist.apply(PageRequest.of(0, 20, Sort.by("code")), ALLOWED);

        // Without a total order, two rows sharing a code can swap places between page requests, which
        // shows one row twice and hides another (edge case EC-API-05).
        assertThat(result.getSort().stream().map(Sort.Order::getProperty)).containsExactly("code", "id");
    }

    @Test
    @DisplayName("an unsorted request still gets a deterministic order")
    void unsortedRequestGetsIdOrder() {
        var result = whitelist.apply(PageRequest.of(0, 20), ALLOWED);

        assertThat(result.getSort().stream().map(Sort.Order::getProperty)).containsExactly("id");
    }

    @Test
    @DisplayName("a size above the maximum is clamped, not rejected")
    void oversizedPageIsClamped() {
        var result = whitelist.apply(PageRequest.of(0, 5_000, Sort.by("code")), ALLOWED);

        // The documented contract clamps rather than failing, and the response reports the effective
        // size so the caller can see what happened (edge case EC-API-01).
        assertThat(result.getPageSize()).isEqualTo(SortWhitelist.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("a size at the maximum is untouched")
    void maximumSizeIsAllowed() {
        var result = whitelist.apply(PageRequest.of(0, 100, Sort.by("code")), ALLOWED);

        assertThat(result.getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("the requested page number is preserved")
    void pageNumberPreserved() {
        var result = whitelist.apply(PageRequest.of(7, 20, Sort.by("code")), ALLOWED);

        assertThat(result.getPageNumber()).isEqualTo(7);
    }

    @Test
    @DisplayName("sort direction is preserved")
    void directionPreserved() {
        var result = whitelist.apply(PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "name")), ALLOWED);

        assertThat(result.getSort().getOrderFor("name").getDirection()).isEqualTo(Sort.Direction.DESC);
    }
}
