package com.dtc.transit.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dtc.transit.masterdata.bus.RegistrationNo;

/** Registration-number normalisation (edge case EC-DATA-01). */
class RegistrationNoTest {

    @ParameterizedTest
    @ValueSource(strings = {"DL1PC1234", "DL 1PC 1234", "dl-1pc-1234", "  DL1PC1234  ", "dl 1pc-1234"})
    @DisplayName("every spelling of one plate normalises to the same value")
    void variantsCollapseToOneForm(String input) {
        // All five are the same bus written by different depots. Without a canonical form the import
        // creates five records and the fleet count is wrong.
        assertThat(RegistrationNo.normalise(input)).isEqualTo("DL1PC1234");
    }

    @Test
    @DisplayName("different plates stay different")
    void distinctPlatesStayDistinct() {
        assertThat(RegistrationNo.normalise("DL1PC1234")).isNotEqualTo(RegistrationNo.normalise("DL1PC1235"));
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> RegistrationNo.normalise(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "---", "   -  "})
    @DisplayName("a value with nothing alphanumeric is rejected rather than stored as empty")
    void emptyAfterNormalisationIsRejected(String input) {
        assertThatThrownBy(() -> RegistrationNo.normalise(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alphanumeric");
    }

    @Test
    @DisplayName("the search form is lenient, so a partial term can be matched")
    void searchFormDoesNotThrow() {
        assertThat(RegistrationNo.normaliseForSearch("dl 1pc")).isEqualTo("DL1PC");
        assertThat(RegistrationNo.normaliseForSearch(null)).isNull();
        assertThat(RegistrationNo.normaliseForSearch("---")).isEmpty();
    }
}
