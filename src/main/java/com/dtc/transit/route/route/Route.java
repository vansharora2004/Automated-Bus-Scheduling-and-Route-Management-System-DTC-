package com.dtc.transit.route.route;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import com.dtc.transit.common.error.BusinessRuleException;
import com.dtc.transit.common.jpa.BaseEntity;
import com.dtc.transit.masterdata.depot.Depot;

/**
 * A public service identified by a route number, operated by one depot.
 *
 * <p>The lifecycle is guarded here rather than in the service layer. A state machine spread across
 * service methods ends up with one path that forgets a rule, and in this case the forgotten rule would
 * let a route go live without anyone approving it.
 */
@Entity
@Table(name = "route")
public class Route extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "route_seq")
    @SequenceGenerator(name = "route_seq", sequenceName = "route_seq", allocationSize = 50)
    private Long id;

    @Column(name = "route_no", nullable = false)
    private String routeNo;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "depot_id", nullable = false)
    private Depot depot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteStatus status = RouteStatus.PROPOSED;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "submitted_by")
    private String submittedBy;

    @Column(name = "decided_by")
    private String decidedBy;

    protected Route() {
        // for JPA
    }

    public Route(String routeNo, String name, Depot depot) {
        this.routeNo = routeNo;
        this.name = name;
        this.depot = depot;
    }

    /**
     * Moves to a new state, refusing anything the lifecycle does not allow.
     *
     * @throws BusinessRuleException if the transition is not permitted from the current state
     */
    public void transitionTo(RouteStatus target, String actor, String note) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessRuleException(
                    "ILLEGAL_ROUTE_TRANSITION",
                    "A route cannot move from " + status + " to " + target + ". Allowed from " + status + ": "
                            + status.allowedNext());
        }
        if (target == RouteStatus.UNDER_REVIEW) {
            this.submittedBy = actor;
        }
        if (target == RouteStatus.APPROVED || target == RouteStatus.REJECTED) {
            this.decidedBy = actor;
            this.decisionNote = note;
        }
        this.status = target;
    }

    /** Geometry may only change while the route is still a draft. */
    public void requireEditable() {
        if (!status.isEditable()) {
            throw new BusinessRuleException(
                    "ROUTE_NOT_EDITABLE",
                    "A route in state " + status + " cannot be edited. Only a PROPOSED route is editable.");
        }
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public void rename(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getRouteNo() {
        return routeNo;
    }

    public String getName() {
        return name;
    }

    public Depot getDepot() {
        return depot;
    }

    public RouteStatus getStatus() {
        return status;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public String getDecidedBy() {
        return decidedBy;
    }
}
