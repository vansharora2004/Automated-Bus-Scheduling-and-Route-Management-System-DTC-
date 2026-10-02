package com.dtc.transit.masterdata.stop;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StopRepository extends JpaRepository<Stop, Long>, JpaSpecificationExecutor<Stop> {

    @Query("select count(s) > 0 from Stop s where upper(s.code) = upper(:code)")
    boolean existsByCodeIgnoreCase(@Param("code") String code);

    @Query("select s from Stop s where upper(s.code) = upper(:code)")
    Optional<Stop> findByCodeIgnoreCase(@Param("code") String code);

    /**
     * Ids of stops within a radius, measured in metres.
     *
     * <p>Native SQL against the projected column. Expressing this in HQL would force a transform at
     * query time, which the GiST index on location_utm cannot serve, turning a point lookup into a full
     * scan (edge case EC-PERF-04).
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT s.id
                    FROM stop s
                    WHERE ST_DWithin(
                            s.location_utm,
                            ST_Transform(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), 32643),
                            :radiusM)
                    """)
    List<Long> findIdsWithinMetres(
            @Param("lon") double longitude, @Param("lat") double latitude, @Param("radiusM") double radiusMetres);
}
