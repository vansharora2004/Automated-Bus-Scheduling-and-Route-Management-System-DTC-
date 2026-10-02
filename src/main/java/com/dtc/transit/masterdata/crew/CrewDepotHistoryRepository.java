package com.dtc.transit.masterdata.crew;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrewDepotHistoryRepository extends JpaRepository<CrewDepotHistory, Long> {

    List<CrewDepotHistory> findByCrewMemberIdOrderByEffectiveFromDesc(Long crewMemberId);

    /** The posting currently open, which is the one a transfer has to close. */
    @Query(
            """
            select h from CrewDepotHistory h
            where h.crewMemberId = :crewMemberId and h.effectiveTo is null
            """)
    Optional<CrewDepotHistory> findOpenPosting(@Param("crewMemberId") Long crewMemberId);
}
