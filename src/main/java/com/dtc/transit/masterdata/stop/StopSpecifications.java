package com.dtc.transit.masterdata.stop;

import jakarta.persistence.criteria.Expression;

import org.springframework.data.jpa.domain.Specification;

import com.dtc.transit.common.filtering.Filters;
import com.dtc.transit.common.geo.GeoSupport;

/** Turns a {@link StopFilter} into predicates. */
public final class StopSpecifications {

    private StopSpecifications() {}

    public static Specification<Stop> of(StopFilter filter) {
        return Specification.allOf(
                freeText(filter.q()),
                Filters.eq("terminal", filter.terminal()),
                Filters.eq("reliefPoint", filter.reliefPoint()),
                Filters.eq("active", filter.active()),
                withinBoundingBox(filter.boundingBox()),
                withinRadius(filter.nearFilter()));
    }

    /** Matches code or name, so a user can type either without choosing a field. */
    private static Specification<Stop> freeText(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        return (root, query, builder) -> builder.or(
                Filters.<Stop>contains("code", term).toPredicate(root, query, builder),
                Filters.<Stop>contains("name", term).toPredicate(root, query, builder));
    }

    /**
     * Restricts to a longitude and latitude box.
     *
     * <p>Compares in 4326 rather than the projected column: a box given in degrees has no exact
     * projected rectangle, and transforming the column would prevent any index from being used.
     */
    private static Specification<Stop> withinBoundingBox(GeoSupport.BoundingBox box) {
        if (box == null) {
            return null;
        }
        return (root, query, builder) -> {
            Expression<?> envelope = builder.function(
                    "ST_MakeEnvelope",
                    Object.class,
                    builder.literal(box.minLon()),
                    builder.literal(box.minLat()),
                    builder.literal(box.maxLon()),
                    builder.literal(box.maxLat()),
                    builder.literal(GeoSupport.SRID_WGS84));
            return builder.isTrue(
                    builder.function("ST_Intersects", Boolean.class, root.get("location"), envelope));
        };
    }

    /**
     * Restricts to a radius in metres.
     *
     * <p>Runs against the projected column, with the transform applied to the parameter instead. Putting
     * the transform on the column would make the GiST index unusable and turn this into a full scan
     * (edge case EC-PERF-04).
     */
    private static Specification<Stop> withinRadius(GeoSupport.NearFilter near) {
        if (near == null) {
            return null;
        }
        return (root, query, builder) -> {
            Expression<?> point = builder.function(
                    "ST_SetSRID",
                    Object.class,
                    builder.function(
                            "ST_MakePoint",
                            Object.class,
                            builder.literal(near.longitude()),
                            builder.literal(near.latitude())),
                    builder.literal(GeoSupport.SRID_WGS84));
            Expression<?> projected = builder.function(
                    "ST_Transform", Object.class, point, builder.literal(GeoSupport.SRID_UTM_43N));
            return builder.isTrue(builder.function(
                    "ST_DWithin",
                    Boolean.class,
                    root.get("locationUtm"),
                    projected,
                    builder.literal(near.radiusMetres())));
        };
    }
}
