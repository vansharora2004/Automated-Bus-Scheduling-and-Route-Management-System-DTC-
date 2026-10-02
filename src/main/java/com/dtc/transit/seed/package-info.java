/**
 * The reproducible S, M and L datasets.
 *
 * <p>Separate from the application's modules on purpose. This is a loader, not a domain: it writes through
 * JDBC rather than through services, and nothing in the system depends on it at runtime.
 */
package com.dtc.transit.seed;
