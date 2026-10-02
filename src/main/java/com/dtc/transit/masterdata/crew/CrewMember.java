package com.dtc.transit.masterdata.crew;

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

/** A driver or conductor. */
@Entity
@Table(name = "crew_member")
public class CrewMember extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "crew_member_seq")
    @SequenceGenerator(name = "crew_member_seq", sequenceName = "crew_member_seq", allocationSize = 50)
    private Long id;

    /**
     * Stored as text.
     *
     * <p>Employee codes carry leading zeros, and a spreadsheet export turns {@code 00123} into
     * {@code 123}. A numeric column would make that loss permanent and silent (edge case EC-DATA-02).
     */
    @Column(name = "employee_code", nullable = false)
    private String employeeCode;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "crew_role", nullable = false)
    private CrewRole crewRole;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "depot_id", nullable = false)
    private Depot depot;

    @Column(name = "licence_no")
    private String licenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "licence_class")
    private LicenceClass licenceClass;

    @Column(name = "licence_expiry")
    private LocalDate licenceExpiry;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CrewStatus status = CrewStatus.ACTIVE;

    /** ISO day of week, 1 = Monday. Null means no fixed weekly off. */
    @Column(name = "weekly_off_dow")
    private Integer weeklyOffDayOfWeek;

    protected CrewMember() {
        // for JPA
    }

    public CrewMember(
            String employeeCode,
            String name,
            CrewRole crewRole,
            Depot depot,
            String licenceNo,
            LicenceClass licenceClass,
            LocalDate licenceExpiry,
            Integer weeklyOffDayOfWeek) {
        this.employeeCode = employeeCode;
        this.name = name;
        this.crewRole = crewRole;
        this.depot = depot;
        this.licenceNo = licenceNo;
        this.licenceClass = licenceClass;
        this.licenceExpiry = licenceExpiry;
        this.weeklyOffDayOfWeek = weeklyOffDayOfWeek;
        requireLicenceForDriver();
    }

    /**
     * A driver record without a licence is unusable.
     *
     * <p>Enforced here as well as by a check constraint, so the caller gets an explanatory 422 instead
     * of a constraint violation surfacing as a 500.
     */
    private void requireLicenceForDriver() {
        if (crewRole.requiresLicence() && (licenceNo == null || licenceExpiry == null)) {
            throw new BusinessRuleException(
                    "LICENCE_REQUIRED", "A driver must have a licence number and expiry date on record");
        }
    }

    /**
     * Whether the licence is valid on a given service date.
     *
     * <p>Expiry is treated as the last valid day, so a licence expiring on the service date still
     * counts. Conductors need no licence, so they are always valid in this sense (edge case EC-CA-01).
     */
    public boolean hasValidLicenceOn(LocalDate serviceDate) {
        if (!crewRole.requiresLicence()) {
            return true;
        }
        return licenceExpiry != null && !licenceExpiry.isBefore(serviceDate);
    }

    public Long getId() {
        return id;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getName() {
        return name;
    }

    public CrewRole getCrewRole() {
        return crewRole;
    }

    public Depot getDepot() {
        return depot;
    }

    public String getLicenceNo() {
        return licenceNo;
    }

    public LicenceClass getLicenceClass() {
        return licenceClass;
    }

    public LocalDate getLicenceExpiry() {
        return licenceExpiry;
    }

    public CrewStatus getStatus() {
        return status;
    }

    public Integer getWeeklyOffDayOfWeek() {
        return weeklyOffDayOfWeek;
    }

    public void changeStatus(CrewStatus status) {
        this.status = status;
    }

    public void transferTo(Depot depot) {
        this.depot = depot;
    }

    public void updateLicence(String licenceNo, LicenceClass licenceClass, LocalDate licenceExpiry) {
        this.licenceNo = licenceNo;
        this.licenceClass = licenceClass;
        this.licenceExpiry = licenceExpiry;
        requireLicenceForDriver();
    }

    public void setWeeklyOffDayOfWeek(Integer weeklyOffDayOfWeek) {
        this.weeklyOffDayOfWeek = weeklyOffDayOfWeek;
    }
}
