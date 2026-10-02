package com.dtc.transit.support;

/**
 * Hand-built geometries with answers worked out independently of the code under test.
 *
 * <p>Spatial bugs are invisible without these. An overlap query with a wrong buffer, a missing transform
 * or a double-counted segment returns a plausible number, and nothing about it looks wrong until it is
 * compared against a shape whose answer was known in advance.
 *
 * <p>All coordinates sit inside Delhi so they pass the service-area check. Longitude comes first, as
 * GeoJSON and PostGIS require.
 *
 * <p>Degree-to-metre conversion at latitude 28.6:
 *
 * <pre>
 * 1 degree latitude  ~ 110,900 m  so 0.001 deg ~ 110.9 m
 * 1 degree longitude ~  97,600 m  so 0.001 deg ~  97.6 m
 * </pre>
 */
public final class GeometryFixtures {

    /** Base longitude for fixtures, inside the Delhi service area. */
    public static final double BASE_LON = 77.2000;

    /** Base latitude for fixtures. */
    public static final double BASE_LAT = 28.6000;

    /** Metres per degree of latitude at this latitude, used to size the fixtures. */
    public static final double METRES_PER_DEGREE_LAT = 110_900;

    /** Metres per degree of longitude at this latitude. */
    public static final double METRES_PER_DEGREE_LON = 97_600;

    private GeometryFixtures() {}

    /**
     * A north-south line of a given length in metres.
     *
     * <p>North-south rather than east-west because a degree of latitude is very close to constant, which
     * makes the intended length predictable without consulting the projection.
     */
    public static String northSouthLine(double lengthMetres) {
        double deltaLat = lengthMetres / METRES_PER_DEGREE_LAT;
        return lineString(BASE_LON, BASE_LAT, BASE_LON, BASE_LAT + deltaLat);
    }

    /** The same line, offset east by a given distance. Used for the parallel-road case. */
    public static String parallelLine(double lengthMetres, double offsetMetres) {
        double deltaLat = lengthMetres / METRES_PER_DEGREE_LAT;
        double deltaLon = offsetMetres / METRES_PER_DEGREE_LON;
        return lineString(BASE_LON + deltaLon, BASE_LAT, BASE_LON + deltaLon, BASE_LAT + deltaLat);
    }

    /**
     * A line crossing the base line at right angles, centred on it.
     *
     * <p>Two routes meeting at a junction share a few metres of tarmac. Reporting that as a shared
     * corridor would flag every pair of routes in the city (edge case EC-GEO-07).
     */
    public static String perpendicularCrossing(double lengthMetres, double atMetresAlong) {
        double atLat = BASE_LAT + atMetresAlong / METRES_PER_DEGREE_LAT;
        double halfLon = (lengthMetres / 2) / METRES_PER_DEGREE_LON;
        return lineString(BASE_LON - halfLon, atLat, BASE_LON + halfLon, atLat);
    }

    /**
     * A line that runs north along the base line, then turns due east.
     *
     * <p>The turn is a right angle on purpose. A shallow divergence stays inside the 25 m corridor for
     * hundreds of metres, so the measured overlap genuinely exceeds the shared leg and the fixture no
     * longer has a known answer. Turning perpendicular leaves the corridor within metres, which makes the
     * expected ratio exactly {@code sharedMetres / (sharedMetres + divergentMetres)}.
     */
    public static String partiallyOverlappingLine(double sharedMetres, double divergentMetres) {
        double sharedLat = BASE_LAT + sharedMetres / METRES_PER_DEGREE_LAT;
        double eastLon = BASE_LON + divergentMetres / METRES_PER_DEGREE_LON;
        return """
                {"type":"LineString","coordinates":[[%s,%s],[%s,%s],[%s,%s]]}"""
                .formatted(BASE_LON, BASE_LAT, BASE_LON, sharedLat, eastLon, sharedLat);
    }

    /**
     * A line that goes north and comes straight back down the same road.
     *
     * <p>Without normalising the geometry its length counts the road twice, which pushes the overlap ratio
     * above 1 and makes a duplicate look like more than a duplicate (edge case EC-GEO-10).
     */
    public static String outAndBackLine(double lengthMetres) {
        double deltaLat = lengthMetres / METRES_PER_DEGREE_LAT;
        return """
                {"type":"LineString","coordinates":[[%s,%s],[%s,%s],[%s,%s]]}"""
                .formatted(BASE_LON, BASE_LAT, BASE_LON, BASE_LAT + deltaLat, BASE_LON, BASE_LAT);
    }

    /** A closed ring, for the loop-direction case. */
    public static String loop(double sideMetres) {
        double dLat = sideMetres / METRES_PER_DEGREE_LAT;
        double dLon = sideMetres / METRES_PER_DEGREE_LON;
        return """
                {"type":"LineString","coordinates":[[%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]]}"""
                .formatted(
                        BASE_LON, BASE_LAT,
                        BASE_LON + dLon, BASE_LAT,
                        BASE_LON + dLon, BASE_LAT + dLat,
                        BASE_LON, BASE_LAT + dLat,
                        BASE_LON, BASE_LAT);
    }

    /** The base line with its coordinate pairs swapped, which puts it in Siberia. */
    public static String swappedCoordinates() {
        return lineString(BASE_LAT, BASE_LON, BASE_LAT + 0.01, BASE_LON);
    }

    /** A line far outside the service area. */
    public static String outsideServiceArea() {
        return lineString(72.8777, 19.0760, 72.8800, 19.0800);
    }

    /** A line with the same point repeated, which has no extent once duplicates collapse. */
    public static String degenerateLine() {
        return lineString(BASE_LON, BASE_LAT, BASE_LON, BASE_LAT);
    }

    /** A zig-zagging line of {@code points} vertices along a short stretch, as a GPS trace would be. */
    public static String noisyLine(int points) {
        var builder = new StringBuilder("{\"type\":\"LineString\",\"coordinates\":[");
        for (int i = 0; i < points; i++) {
            double lat = BASE_LAT + (i * 0.00002);
            // Alternating east-west wobble of a few metres, the signature of a traced line.
            double lon = BASE_LON + ((i % 2 == 0) ? 0.00002 : -0.00002);
            builder.append(i > 0 ? "," : "").append("[").append(lon).append(",").append(lat).append("]");
        }
        return builder.append("]}").toString();
    }

    /** GeoJSON for a point, for stop fixtures. */
    public static String point(double lon, double lat) {
        return """
                {"type":"Point","coordinates":[%s,%s]}""".formatted(lon, lat);
    }

    /** A stop on the base line, a given distance along it. */
    public static double[] stopAlongBaseLine(double metresAlong) {
        return new double[] {BASE_LON, BASE_LAT + metresAlong / METRES_PER_DEGREE_LAT};
    }

    private static String lineString(double lon1, double lat1, double lon2, double lat2) {
        return """
                {"type":"LineString","coordinates":[[%s,%s],[%s,%s]]}"""
                .formatted(lon1, lat1, lon2, lat2);
    }
}
