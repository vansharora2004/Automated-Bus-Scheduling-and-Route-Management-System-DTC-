package com.dtc.transit.seed;

/**
 * The three dataset sizes the project is measured against.
 *
 * <p>Everything is derived from one number, the route count, so the three sizes differ in scale and not in
 * shape. A dataset that changed shape with size would make a performance comparison between S and L
 * meaningless: the question "does this get slower" needs the same workload at three volumes.
 *
 * <p>The per-route multipliers come from the figures in the implementation plan: roughly 60 trips, 6 buses,
 * 15 crew members and 12 stops for every route.
 *
 * @param depots how many depots the routes are spread across
 * @param routesPerDepot routes each depot owns
 * @param seed fixed per size, so the same size always regenerates byte-for-byte
 */
public enum DatasetSize {
    S(1, 20, 20_260_501L),
    M(10, 20, 20_260_502L),
    L(45, 19, 20_260_503L);

    /** Stops laid along each pattern. Also the vertex count of the generated line. */
    public static final int STOPS_PER_ROUTE = 12;

    /** Buses per route, the figure that turns 20 routes into roughly 120 buses. */
    public static final int BUSES_PER_ROUTE = 6;

    public static final int CREW_PER_ROUTE = 15;

    private final int depots;
    private final int routesPerDepot;
    private final long seed;

    DatasetSize(int depots, int routesPerDepot, long seed) {
        this.depots = depots;
        this.routesPerDepot = routesPerDepot;
        this.seed = seed;
    }

    public int depots() {
        return depots;
    }

    public int routesPerDepot() {
        return routesPerDepot;
    }

    public long seed() {
        return seed;
    }

    public int routes() {
        return depots * routesPerDepot;
    }

    public int stops() {
        return routes() * STOPS_PER_ROUTE;
    }

    public int buses() {
        return routes() * BUSES_PER_ROUTE;
    }

    public int crew() {
        return routes() * CREW_PER_ROUTE;
    }
}
