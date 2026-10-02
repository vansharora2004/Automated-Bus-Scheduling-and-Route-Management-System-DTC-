package com.dtc.transit.masterdata.crew;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CrewLeaveRepository extends JpaRepository<CrewLeave, Long> {

    List<CrewLeave> findByCrewMemberIdOrderByStartsAtAsc(Long crewMemberId);

    /**
     * Crew members with leave overlapping a window.
     *
     * <p>Half-open comparison, matching the '[)' bounds of the generated range column, so leave ending
     * exactly when a duty starts does not count as a clash.
     */
    @Query(
            """
            select distinct l.crewMemberId from CrewLeave l
            where l.startsAt < :to and l.endsAt > :from
            """)
    List<Long> findCrewMemberIdsOnLeaveBetween(@Param("from") Instant from, @Param("to") Instant to);
}
