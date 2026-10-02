package com.dtc.transit.masterdata.crew;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrewQualificationRepository
        extends JpaRepository<CrewQualification, CrewQualification.Key> {

    List<CrewQualification> findByKeyCrewMemberId(Long crewMemberId);

    @Query("select q.key.crewMemberId from CrewQualification q where upper(q.key.code) = upper(:code)")
    List<Long> findCrewMemberIdsHolding(@Param("code") String code);
}
