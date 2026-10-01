package com.dtc.transit.common.config;

import java.time.LocalTime;
import java.time.ZoneId;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Presentation timezone and the service-day boundary.
 *
 * <p>Instants are stored in UTC and converted to this zone only at API edges. The service day may
 * start after midnight (default 03:00 IST), so a trip departing at 00:40 belongs to the previous
 * service date (edge case EC-TIME-03).
 */
@Validated
@ConfigurationProperties(prefix = "app.time")
public record AppTimeProperties(@NotNull ZoneId zone, @NotNull LocalTime serviceDayStart) {

    public AppTimeProperties {
        if (zone == null) {
            zone = ZoneId.of("Asia/Kolkata");
        }
        if (serviceDayStart == null) {
            serviceDayStart = LocalTime.of(3, 0);
        }
    }
}
