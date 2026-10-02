package com.dtc.transit.route.pattern;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServiceAreaRepository extends JpaRepository<ServiceArea, Long> {

    @Query("select a from ServiceArea a where a.active = true")
    Optional<ServiceArea> findActive();

    /**
     * Whether a geometry lies inside the area.
     *
     * <p>Native, because the containment test belongs in PostGIS: doing it in Java would mean loading the
     * whole boundary polygon into the JVM on every route submission.
     *
     * <p>{@code ST_Covers} rather than {@code ST_Contains}: a route that runs along the boundary touches
     * it, and {@code ST_Contains} is false for geometry on the border.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT ST_Covers(a.geom, ST_SetSRID(ST_GeomFromText(:wkt), 4326))
                    FROM service_area a
                    WHERE a.id = :id
                    """)
    boolean containsGeometry(@Param("id") Long id, @Param("wkt") String wellKnownText);
}
