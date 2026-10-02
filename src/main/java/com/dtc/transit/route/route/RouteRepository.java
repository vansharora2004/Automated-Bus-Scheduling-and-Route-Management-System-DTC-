package com.dtc.transit.route.route;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RouteRepository extends JpaRepository<Route, Long>, JpaSpecificationExecutor<Route> {

    /**
     * Whether a route number is in use by a route that has not been retired.
     *
     * <p>Mirrors the partial unique index: a retired number is free to reuse, while history keeps the old
     * route (edge case EC-GEO-21).
     */
    @Query("select count(r) > 0 from Route r where upper(r.routeNo) = upper(:routeNo) and r.status <> 'RETIRED'")
    boolean existsLiveRouteNo(@Param("routeNo") String routeNo);

    @Query("select r from Route r where upper(r.routeNo) = upper(:routeNo) and r.status <> 'RETIRED'")
    Optional<Route> findLiveByRouteNo(@Param("routeNo") String routeNo);

    List<Route> findByStatus(RouteStatus status);
}
