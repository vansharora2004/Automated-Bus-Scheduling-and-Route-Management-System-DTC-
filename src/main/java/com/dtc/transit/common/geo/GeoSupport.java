package com.dtc.transit.common.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Point geometry and spatial request parameters.
 *
 * <p>Phase 3 needs only points, for depots and stops. Line geometry, GeoJSON documents and the full
 * validation pipeline arrive with routes in Phase 4.
 */
public final class GeoSupport {

    /** WGS84, the interchange CRS, matching what GeoJSON and every GPS device speak. */
    public static final int SRID_WGS84 = 4326;

    /** UTM zone 43N, the metric CRS covering Delhi, used for anything measured in metres. */
    public static final int SRID_UTM_43N = 32643;

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING), SRID_WGS84);

    private GeoSupport() {}

    /**
     * Builds a point from longitude and latitude, in that order.
     *
     * <p>The order is the single most common geospatial mistake: GeoJSON and PostGIS take
     * {@code (lon, lat)}, while people say "lat, long" and most mapping UIs display it that way.
     *
     * <p>Range checking catches only the swaps it can. Delhi is roughly 77E 28N, and swapping those gives
     * latitude 77, which is a perfectly valid latitude in northern Siberia. Detecting that case needs the
     * service-area polygon, which Phase 4 adds along with the rest of the geometry validation pipeline
     * (edge case EC-GEO-01). What is caught here is any value outside the coordinate system itself, which
     * includes a swap of a location far enough north or south.
     */
    public static Point point(double longitude, double latitude) {
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException(
                    "longitude " + longitude + " is out of range; expected -180..180 (lon, lat order)");
        }
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude " + latitude
                    + " is out of range; expected -90..90. Coordinates may be swapped (expected lon, lat)");
        }
        Point point = FACTORY.createPoint(new Coordinate(longitude, latitude));
        point.setSRID(SRID_WGS84);
        return point;
    }

    /**
     * A bounding box parsed from {@code minLon,minLat,maxLon,maxLat}.
     *
     * @param minLon western edge
     * @param minLat southern edge
     * @param maxLon eastern edge
     * @param maxLat northern edge
     */
    public record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {

        /**
         * Parses the query-parameter form.
         *
         * @throws IllegalArgumentException if the value is not four numbers in range and correctly
         *     ordered (edge case EC-API-10)
         */
        public static BoundingBox parse(String value) {
            String[] parts = value.split(",");
            if (parts.length != 4) {
                throw new IllegalArgumentException(
                        "bbox must be 'minLon,minLat,maxLon,maxLat' but had " + parts.length + " values");
            }
            double minLon = number(parts[0], "minLon");
            double minLat = number(parts[1], "minLat");
            double maxLon = number(parts[2], "maxLon");
            double maxLat = number(parts[3], "maxLat");

            if (minLon > maxLon) {
                throw new IllegalArgumentException("bbox minLon " + minLon + " is east of maxLon " + maxLon);
            }
            if (minLat > maxLat) {
                throw new IllegalArgumentException("bbox minLat " + minLat + " is north of maxLat " + maxLat);
            }
            // Validate the corners, which also catches a swapped-coordinate bbox.
            point(minLon, minLat);
            point(maxLon, maxLat);
            return new BoundingBox(minLon, minLat, maxLon, maxLat);
        }

        private static double number(String raw, String name) {
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bbox " + name + " '" + raw + "' is not a number", e);
            }
        }
    }

    /**
     * A point-and-radius filter parsed from {@code near=lon,lat} plus {@code radiusM}.
     *
     * @param radiusMetres measured in metres, which is why the query runs against the projected column
     */
    public record NearFilter(double longitude, double latitude, double radiusMetres) {

        public static final double DEFAULT_RADIUS_METRES = 500;

        /** Largest radius accepted, so one request cannot ask for the whole network. */
        public static final double MAX_RADIUS_METRES = 50_000;

        public static NearFilter parse(String near, Double radiusMetres) {
            String[] parts = near.split(",");
            if (parts.length != 2) {
                throw new IllegalArgumentException("near must be 'lon,lat' but had " + parts.length + " values");
            }
            double longitude;
            double latitude;
            try {
                longitude = Double.parseDouble(parts[0].trim());
                latitude = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("near '" + near + "' is not a 'lon,lat' pair", e);
            }
            point(longitude, latitude);

            double radius = radiusMetres == null ? DEFAULT_RADIUS_METRES : radiusMetres;
            if (radius <= 0 || radius > MAX_RADIUS_METRES) {
                throw new IllegalArgumentException(
                        "radiusM must be between 1 and " + (long) MAX_RADIUS_METRES + " metres");
            }
            return new NearFilter(longitude, latitude, radius);
        }
    }
}
