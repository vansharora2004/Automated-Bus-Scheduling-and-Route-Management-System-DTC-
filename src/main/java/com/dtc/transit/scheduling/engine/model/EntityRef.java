package com.dtc.transit.scheduling.engine.model;

/**
 * A pointer from a conflict to the row it is about.
 *
 * <p>A record pair rather than a map entry, so a conflict's references keep the order they were added in.
 * Hash-map ordering would make two runs over identical input produce different JSON, which breaks the
 * determinism the whole pipeline is built on.
 */
public record EntityRef(String entityType, long entityId) {}
