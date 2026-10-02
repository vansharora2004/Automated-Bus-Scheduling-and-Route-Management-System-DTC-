package com.dtc.transit.masterdata.stop;

import com.dtc.transit.common.geo.GeoSupport;

/**
 * Query parameters for the stop list.
 *
 * <p>Bound straight from the query string, so a caller sees 400 for a value of the wrong type rather
 * than having it silently ignored.
 *
 * @param q            free-text term matched against code and name
 * @param terminal     restrict to terminals
 * @param reliefPoint  restrict to relief points
 * @param active       restrict by active flag; null returns both
 * @param bbox         {@code minLon,minLat,maxLon,maxLat}
 * @param near         {@code lon,lat}
 * @param radiusM      radius for {@code near}, in metres
 */
public record StopFilter(
        String q, Boolean terminal, Boolean reliefPoint, Boolean active, String bbox, String near, Double radiusM) {

    public GeoSupport.BoundingBox boundingBox() {
        return bbox == null || bbox.isBlank() ? null : GeoSupport.BoundingBox.parse(bbox);
    }

    public GeoSupport.NearFilter nearFilter() {
        return near == null || near.isBlank() ? null : GeoSupport.NearFilter.parse(near, radiusM);
    }
}
