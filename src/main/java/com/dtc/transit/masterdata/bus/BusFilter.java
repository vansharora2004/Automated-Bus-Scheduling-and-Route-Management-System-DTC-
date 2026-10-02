package com.dtc.transit.masterdata.bus;

/**
 * Query parameters for the bus list.
 *
 * @param depotId   restrict to one depot; a depot-bound caller is confined to their own regardless
 * @param status    fleet availability
 * @param busType   physical configuration
 * @param fuelType  propulsion
 * @param ac        air-conditioned
 * @param q         free-text term matched against registration and fleet number
 */
public record BusFilter(
        Long depotId, BusStatus status, BusType busType, FuelType fuelType, Boolean ac, String q) {}
