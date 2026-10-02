package com.dtc.transit.scheduling.rules;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dtc.transit.common.jpa.BaseEntity;

/**
 * A rule set as stored: a scope, a date it starts applying, and the rules as JSON.
 *
 * <p>Named {@code StoredRuleSet} because {@link com.dtc.transit.scheduling.engine.model.RuleSet} is the typed
 * record the engine works with. Keeping them apart is what lets the engine stay free of JPA while the rules
 * still live in the database.
 *
 * <p>The JSON is held as a string and bound by {@link RuleSetResolver}. Mapping it to the record directly would
 * drag a Jackson dependency into the entity and give the engine's record two masters.
 */
@Entity
@Table(name = "rule_set")
public class StoredRuleSet extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "rule_set_seq")
    @SequenceGenerator(name = "rule_set_seq", sequenceName = "rule_set_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** Null means global. A depot-specific set wins over the global one for the same date. */
    @Column(name = "depot_id")
    private Long depotId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String rules;

    protected StoredRuleSet() {
        // for JPA
    }

    public StoredRuleSet(String name, Long depotId, LocalDate effectiveFrom, String rules) {
        this.name = name;
        this.depotId = depotId;
        this.effectiveFrom = effectiveFrom;
        this.rules = rules;
    }

    public boolean isGlobal() {
        return depotId == null;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Long getDepotId() {
        return depotId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getRules() {
        return rules;
    }
}
