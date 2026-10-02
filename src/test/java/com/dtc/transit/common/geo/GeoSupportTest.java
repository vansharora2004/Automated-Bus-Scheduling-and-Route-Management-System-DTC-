package com.dtc.transit.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Point construction and spatial request parameters, including the swapped-coordinate case. */
class GeoSupportTest {

    @Nested
    @DisplayName("point")
    class PointConstruction {

        @Test
        @DisplayName("takes longitude first, matching GeoJSON and PostGIS")
        void longitudeFirst() {
            var point = GeoSupport.point(77.2090, 28.6139);

            assertThat(point.getX()).isEqualTo(77.2090);
            assertThat(point.getY()).isEqualTo(28.6139);
            assertThat(point.getSRID()).isEqualTo(GeoSupport.SRID_WGS84);
        }

        @Test
        @DisplayName("a swap that pushes latitude out of range is caught, with a hint")
        void swapOutsideLatitudeRangeIsCaught() {
            // Longitude 100E swapped into the latitude slot is impossible, so this one is detectable
            // from the values alone.
            assertThatThrownBy(() -> GeoSupport.point(20.0, 100.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("swapped");
        }

        @Test
        @DisplayName("EC-GEO-01: a swap within valid ranges is NOT detectable here, and needs Phase 4")
        void swapWithinValidRangesPassesRangeChecking() {
            // Delhi is roughly 77E 28N. Swapped, that reads 28E 77N, and latitude 77 is a perfectly
            // valid latitude in northern Siberia. No range check can reject this; only containment in
            // the service-area polygon can, which Phase 4 adds with the geometry validation pipeline.
            // Asserting a throw here would be asserting behaviour the system cannot have.
            assertThatCode(() -> GeoSupport.point(28.6139, 77.2090)).doesNotThrowAnyException();
        }

        @Test
        void outOfRangeLongitudeRejected() {
            assertThatThrownBy(() -> GeoSupport.point(181, 0)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("bbox")
    class BoundingBoxParsing {

        @Test
        void parsesFourOrderedNumbers() {
            var box = GeoSupport.BoundingBox.parse("77.0,28.4,77.4,28.8");

            assertThat(box.minLon()).isEqualTo(77.0);
            assertThat(box.maxLat()).isEqualTo(28.8);
        }

        @Test
        @DisplayName("EC-API-10: the wrong number of values is rejected")
        void wrongArityRejected() {
            assertThatThrownBy(() -> GeoSupport.BoundingBox.parse("77.0,28.4,77.4"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("3 values");
        }

        @Test
        @DisplayName("EC-API-10: inverted edges are rejected")
        void invertedBoxRejected() {
            assertThatThrownBy(() -> GeoSupport.BoundingBox.parse("77.4,28.4,77.0,28.8"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("east of");
        }

        @Test
        void nonNumericRejected() {
            assertThatThrownBy(() -> GeoSupport.BoundingBox.parse("77.0,28.4,east,28.8"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a number");
        }
    }

    @Nested
    @DisplayName("near")
    class NearParsing {

        @Test
        void defaultsTheRadius() {
            var near = GeoSupport.NearFilter.parse("77.2,28.6", null);

            assertThat(near.radiusMetres()).isEqualTo(GeoSupport.NearFilter.DEFAULT_RADIUS_METRES);
        }

        @Test
        void acceptsAnExplicitRadius() {
            assertThat(GeoSupport.NearFilter.parse("77.2,28.6", 1_500.0).radiusMetres())
                    .isEqualTo(1_500.0);
        }

        @Test
        @DisplayName("an unbounded radius is refused, so one request cannot scan the network")
        void oversizedRadiusRejected() {
            assertThatThrownBy(() -> GeoSupport.NearFilter.parse("77.2,28.6", 10_000_000.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("radiusM");
        }

        @Test
        void zeroRadiusRejected() {
            assertThatThrownBy(() -> GeoSupport.NearFilter.parse("77.2,28.6", 0.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void malformedPairRejected() {
            assertThatThrownBy(() -> GeoSupport.NearFilter.parse("77.2", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void validPairAccepted() {
            assertThatCode(() -> GeoSupport.NearFilter.parse("77.2090,28.6139", 500.0))
                    .doesNotThrowAnyException();
        }
    }
}
