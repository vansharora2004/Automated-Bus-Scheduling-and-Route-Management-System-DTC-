package com.dtc.transit.masterdata.depot;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepotRepository extends JpaRepository<Depot, Long>, JpaSpecificationExecutor<Depot> {

    /** Matched case-insensitively, mirroring the unique index on upper(code). */
    @Query("select d from Depot d where upper(d.code) = upper(:code)")
    Optional<Depot> findByCodeIgnoreCase(@Param("code") String code);

    @Query("select count(d) > 0 from Depot d where upper(d.code) = upper(:code)")
    boolean existsByCodeIgnoreCase(@Param("code") String code);
}
