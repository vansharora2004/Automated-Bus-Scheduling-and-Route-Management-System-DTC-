/**
 * The scheduling engine: plain Java, no framework.
 *
 * <p>Nothing here may import Spring, JPA, Hibernate, a servlet API or Jackson, and nothing here may reach into
 * another module. An architecture test enforces both, because that purity is what makes the algorithms
 * deterministic and testable in milliseconds, and it erodes silently the first time someone injects a
 * repository into a loop.
 */
package com.dtc.transit.scheduling.engine;
