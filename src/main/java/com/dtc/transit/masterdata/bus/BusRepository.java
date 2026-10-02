package com.dtc.transit.masterdata.bus;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BusRepository extends JpaRepository<Bus, Long>, JpaSpecificationExecutor<Bus> {

    Optional<Bus> findByRegistrationNo(String normalisedRegistrationNo);

    boolean existsByRegistrationNo(String normalisedRegistrationNo);

    @Query("select count(b) > 0 from Bus b where b.depot.id = :depotId and upper(b.fleetNo) = upper(:fleetNo)")
    boolean existsByDepotAndFleetNo(@Param("depotId") Long depotId, @Param("fleetNo") String fleetNo);
}
