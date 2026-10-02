package com.dtc.transit.route.pattern;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.geo.GeoSupport;

/**
 * Validates and cleans a submitted route pattern.
 *
 * <p>The stages run in a deliberate order, each one making the next meaningful:
 *
 * <ol>
 *   <li>coordinate ranges, which catches values outside the coordinate system
 *   <li>service-area containment, which catches a lon/lat swap that ranges cannot
 *   <li>at least two distinct points, after collapsing consecutive duplicates
 *   <li>topological validity
 *   <li>optional simplification of a noisy GPS trace
 *   <li>a vertex cap, applied last so simplification gets a chance to come under it
 * </ol>
 *
 * <p>Containment comes second on purpose. Running it after simplification would mean spending work on a
 * geometry that is in the wrong hemisphere.
 */
@Component
public class GeometryValidator {

    private static final Logger log = LoggerFactory.getLogger(GeometryValidator.class);

    /** Tolerance for simplifying a traced polyline, in metres of longitude-equivalent at this latitude. */
    private static final double SIMPLIFY_TOLERANCE_DEGREES = 0.00005;

    /** Hard cap on vertices, which bounds the CPU one request can ask the database to spend. */
    public static final int MAX_VERTICES = 5_000;

    private final ServiceAreaRepository serviceAreas;

    public GeometryValidator(ServiceAreaRepository serviceAreas) {
        this.serviceAreas = serviceAreas;
    }

    /**
     * Runs the pipeline.
     *
     * @param simplify whether to simplify a noisy trace before the vertex cap is applied
     * @return the cleaned geometry, which may have fewer points than was submitted
     */
    public Result validate(LineString submitted, boolean simplify) {
        List<String> warnings = new ArrayList<>();

        requireCoordinatesInRange(submitted);
        requireWithinServiceArea(submitted, warnings);

        LineString deduplicated = removeConsecutiveDuplicates(submitted, warnings);
        requireTopologicallyValid(deduplicated);

        LineString cleaned = deduplicated;
        if (simplify) {
            cleaned = simplify(cleaned, warnings);
        }
        requireVertexCapRespected(cleaned, simplify);

        return new Result(cleaned, warnings);
    }

    private void requireCoordinatesInRange(LineString line) {
        for (Coordinate coordinate : line.getCoordinates()) {
            // Reuses the point factory purely for its range checks and swap hint.
            GeoSupport.point(coordinate.x, coordinate.y);
        }
    }

    /**
     * Rejects geometry outside the operating area.
     *
     * <p>This is the only check that catches a Delhi lon/lat swap. Swapping 77E 28N gives 28E 77N, which
     * is a valid coordinate pair in northern Siberia, so range checking passes it happily
     * (edge case EC-GEO-01).
     */
    private void requireWithinServiceArea(LineString line, List<String> warnings) {
        var area = serviceAreas.findActive();
        if (area.isEmpty()) {
            // Should not happen: a default area is seeded by migration. Worth a loud warning rather
            // than silence, because losing this check removes the swap protection entirely.
            log.warn("No active service area configured; skipping containment validation");
            warnings.add("No active service area is configured, so the geometry was not checked against it");
            return;
        }

        boolean contained = serviceAreas.containsGeometry(area.get().getId(), line.toText());
        if (!contained) {
            Coordinate first = line.getCoordinateN(0);
            throw new BusinessRuleException(
                    "OUTSIDE_SERVICE_AREA",
                    ("The geometry falls outside the service area '%s'. First point is (%.4f, %.4f). "
                                    + "Coordinates must be [longitude, latitude] in that order; "
                                    + "(%.4f, %.4f) would be inside.")
                            .formatted(area.get().getName(), first.x, first.y, first.y, first.x));
        }
    }

    /**
     * Collapses repeated points.
     *
     * <p>A traced line often repeats a vertex where the recorder paused. Those add no shape but do break
     * length and simplification, so they go before anything else measures the line.
     */
    private LineString removeConsecutiveDuplicates(LineString line, List<String> warnings) {
        Coordinate[] original = line.getCoordinates();
        List<Coordinate> kept = new ArrayList<>(original.length);
        for (Coordinate coordinate : original) {
            if (kept.isEmpty() || !kept.get(kept.size() - 1).equals2D(coordinate)) {
                kept.add(coordinate);
            }
        }

        // The count is checked before a LineString is built, not after. JTS refuses to construct a
        // one-point LineString at all, so building first turns a clear "too few points" rejection into an
        // opaque IllegalArgumentException and the wrong status code.
        if (kept.size() < 2) {
            throw new BusinessRuleException(
                    "GEOMETRY_TOO_FEW_POINTS",
                    "A route pattern needs at least two distinct points; after removing repeated points only "
                            + kept.size() + " remained");
        }

        if (kept.size() == original.length) {
            return line;
        }
        warnings.add("Removed " + (original.length - kept.size()) + " repeated point(s)");
        LineString cleaned = line.getFactory().createLineString(kept.toArray(Coordinate[]::new));
        cleaned.setSRID(GeoSupport.SRID_WGS84);
        return cleaned;
    }

    private void requireTopologicallyValid(LineString line) {
        var validOp = new IsValidOp(line);
        if (!validOp.isValid()) {
            String reason = validOp.getValidationError() == null
                    ? "unknown"
                    : validOp.getValidationError().getMessage();
            throw new BusinessRuleException("GEOMETRY_INVALID", "The geometry is not valid: " + reason);
        }
    }

    private LineString simplify(LineString line, List<String> warnings) {
        var simplified = TopologyPreservingSimplifier.simplify(line, SIMPLIFY_TOLERANCE_DEGREES);
        if (!(simplified instanceof LineString result)) {
            // Simplification collapsed the line entirely, which means it had no real extent.
            throw new BusinessRuleException(
                    "GEOMETRY_DEGENERATE", "The geometry collapsed when simplified; it has no usable extent");
        }
        if (result.getNumPoints() < line.getNumPoints()) {
            warnings.add("Simplified from " + line.getNumPoints() + " to " + result.getNumPoints() + " points");
        }
        result.setSRID(GeoSupport.SRID_WGS84);
        return result;
    }

    private void requireVertexCapRespected(LineString line, boolean simplified) {
        if (line.getNumPoints() > MAX_VERTICES) {
            throw new BusinessRuleException(
                    "GEOMETRY_TOO_COMPLEX",
                    "The geometry has " + line.getNumPoints() + " points, above the limit of " + MAX_VERTICES
                            + (simplified ? ". Simplification was not enough." : ". Try submitting with simplify=true."));
        }
    }

    /**
     * @param geometry the cleaned line, ready to store
     * @param warnings things the planner should know that are not errors
     */
    public record Result(LineString geometry, List<String> warnings) {}
}
