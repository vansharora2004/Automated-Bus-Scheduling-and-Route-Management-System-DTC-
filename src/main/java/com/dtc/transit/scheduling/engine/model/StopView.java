package com.dtc.transit.scheduling.engine.model;

/**
 * The scheduling-relevant facts about a stop.
 *
 * <p>Only three flags, because that is all the engine needs. Geometry stays in the database, where
 * distance questions are answered before the snapshot is taken.
 */
public record StopView(long id, boolean terminal, boolean reliefPoint, boolean crewFacilities) {}
