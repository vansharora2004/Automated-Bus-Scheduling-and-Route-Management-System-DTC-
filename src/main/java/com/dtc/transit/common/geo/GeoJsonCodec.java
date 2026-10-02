package com.dtc.transit.common.geo;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.stereotype.Component;

import com.dtc.transit.common.error.BusinessRuleException;

/**
 * Reads and writes geometry as GeoJSON.
 *
 * <p>GeoJSON is the interchange format because it is what mapping tools emit and consume, and it fixes
 * the coordinate order as {@code (lon, lat)} in WGS84. The SRID is therefore implied rather than
 * declared, which is why a payload carrying its own {@code crs} member is refused rather than trusted.
 */
@Component
public class GeoJsonCodec {

    public LineString readLineString(String geoJson) {
        Geometry geometry = read(geoJson);
        if (!(geometry instanceof LineString lineString)) {
            throw new BusinessRuleException(
                    "GEOMETRY_WRONG_TYPE",
                    "Expected a LineString but received " + geometry.getGeometryType()
                            + ". A route pattern is a single path; use one LineString per direction.");
        }
        lineString.setSRID(GeoSupport.SRID_WGS84);
        return lineString;
    }

    public Point readPoint(String geoJson) {
        Geometry geometry = read(geoJson);
        if (!(geometry instanceof Point point)) {
            throw new BusinessRuleException(
                    "GEOMETRY_WRONG_TYPE", "Expected a Point but received " + geometry.getGeometryType());
        }
        point.setSRID(GeoSupport.SRID_WGS84);
        return point;
    }

    /** Serialises geometry for a response. */
    public String write(Geometry geometry) {
        var writer = new GeoJsonWriter();
        // JTS would otherwise emit a "crs" member naming EPSG:4326. GeoJSON fixes the CRS, so the
        // member is redundant at best and contradictory at worst.
        writer.setEncodeCRS(false);
        return writer.write(geometry);
    }

    private Geometry read(String geoJson) {
        if (geoJson == null || geoJson.isBlank()) {
            throw new BusinessRuleException("GEOMETRY_MISSING", "No geometry was supplied");
        }
        if (geoJson.contains("\"crs\"")) {
            // A declared CRS means the client believes the coordinates are in something other than
            // WGS84, and reprojecting on a guess would silently move the route (edge case EC-GEO-02).
            throw new BusinessRuleException(
                    "GEOMETRY_CRS_NOT_SUPPORTED",
                    "Geometry must be plain GeoJSON in WGS84 (EPSG:4326) with no 'crs' member");
        }
        try {
            Geometry geometry = new GeoJsonReader().read(geoJson);
            if (geometry == null || geometry.isEmpty()) {
                throw new BusinessRuleException("GEOMETRY_EMPTY", "The geometry is empty");
            }
            return geometry;
        } catch (ParseException e) {
            throw new BusinessRuleException(
                    "GEOMETRY_UNPARSEABLE", "The geometry is not valid GeoJSON: " + e.getMessage());
        }
    }
}
