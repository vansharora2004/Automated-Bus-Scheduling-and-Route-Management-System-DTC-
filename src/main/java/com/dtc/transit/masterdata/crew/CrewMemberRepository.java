package com.dtc.transit.masterdata.crew;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrewMemberRepository
        extends JpaRepository<CrewMember, Long>, JpaSpecificationExecutor<CrewMember> {

    @Query("select c from CrewMember c where upper(c.employeeCode) = upper(:code)")
    Optional<CrewMember> findByEmployeeCodeIgnoreCase(@Param("code") String employeeCode);

    @Query("select count(c) > 0 from CrewMember c where upper(c.employeeCode) = upper(:code)")
    boolean existsByEmployeeCodeIgnoreCase(@Param("code") String employeeCode);
}
