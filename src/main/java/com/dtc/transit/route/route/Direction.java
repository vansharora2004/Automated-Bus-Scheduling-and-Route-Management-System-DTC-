package com.dtc.transit.route.route;

/**
 * Travel direction of a pattern.
 *
 * <p>LOOP is not merely a label: a circular route has no meaningful start-to-end orientation, so
 * direction agreement between two loops has to be judged from segment orientation rather than endpoints
 * (edge case EC-GEO-09).
 */
public enum Direction {
    UP,
    DOWN,
    LOOP;

    public boolean isLoop() {
        return this == LOOP;
    }
}
