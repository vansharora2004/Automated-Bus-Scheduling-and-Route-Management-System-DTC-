package com.dtc.transit.timetable.timetable;

/**
 * Timetable lifecycle.
 *
 * <p>Only an ACTIVE timetable is used to resolve trips for a service date, and the database refuses two
 * active timetables covering the same route, day type and dates.
 */
public enum TimetableStatus {
    /** Being built. Bands may be edited and trips regenerated freely. */
    DRAFT,
    /** In force for its validity window. */
    ACTIVE,
    /** Replaced. Kept so past dates still resolve to what actually ran. */
    RETIRED;

    public boolean isEditable() {
        return this == DRAFT;
    }
}
