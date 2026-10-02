package com.dtc.transit.scheduling.rules;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredRuleSetRepository extends JpaRepository<StoredRuleSet, Long> {

    /**
     * The rule set in force for a depot on a date.
     *
     * <p>Ordered so a depot-specific set comes before the global one, then latest effective date first. One
     * query rather than two: a depot lookup followed by a global fallback would need the application to
     * re-implement the precedence, and the two could then disagree.
     */
    @Query(
            """
            select r from StoredRuleSet r
            where r.effectiveFrom <= :serviceDate
              and (r.depotId = :depotId or r.depotId is null)
            order by case when r.depotId is null then 1 else 0 end, r.effectiveFrom desc
            limit 1
            """)
    Optional<StoredRuleSet> findInForce(
            @Param("depotId") Long depotId, @Param("serviceDate") LocalDate serviceDate);

    @Query(
            """
            select r from StoredRuleSet r
            where r.depotId is null and r.effectiveFrom <= :serviceDate
            order by r.effectiveFrom desc
            limit 1
            """)
    Optional<StoredRuleSet> findGlobalInForce(@Param("serviceDate") LocalDate serviceDate);

    List<StoredRuleSet> findByDepotIdOrderByEffectiveFromDesc(Long depotId);

    Page<StoredRuleSet> findAllByOrderByEffectiveFromDescIdDesc(Pageable pageable);
}
