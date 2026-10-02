package com.dtc.transit.scheduling.duty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import com.dtc.transit.common.jpa.BaseEntity;
import com.dtc.transit.scheduling.engine.model.DutyType;
import com.dtc.transit.scheduling.engine.model.SchedulingMode;

/**
 * One person's working day, as stored.
 *
 * <p>The metrics are columns rather than something derived on read. They were computed against the rule set in
 * force for the run, and recomputing them under today's rules would answer a different question and quietly
 * change what a crew was paid for.
 */
@Entity
@Table(name = "duty")
public class Duty extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "duty_seq")
    @SequenceGenerator(name = "duty_seq", sequenceName = "duty_seq", allocationSize = 50)
    private Long id;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "duty_no", nullable = false)
    private int dutyNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SchedulingMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "duty_type", nullable = false)
    private DutyType dutyType;

    @Column(name = "sign_on_sec", nullable = false)
    private int signOnSec;

    @Column(name = "sign_off_sec", nullable = false)
    private int signOffSec;

    @Column(name = "platform_sec", nullable = false)
    private int platformSec;

    @Column(name = "paid_sec", nullable = false)
    private int paidSec;

    @Column(name = "break_sec", nullable = false)
    private int breakSec;

    @Column(name = "spread_sec", nullable = false)
    private int spreadSec;

    @Column(name = "overtime_sec", nullable = false)
    private int overtimeSec;

    protected Duty() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public int getDutyNo() {
        return dutyNo;
    }

    public SchedulingMode getMode() {
        return mode;
    }

    public DutyType getDutyType() {
        return dutyType;
    }

    public int getSignOnSec() {
        return signOnSec;
    }

    public int getSignOffSec() {
        return signOffSec;
    }

    public int getPlatformSec() {
        return platformSec;
    }

    public int getPaidSec() {
        return paidSec;
    }

    public int getBreakSec() {
        return breakSec;
    }

    public int getSpreadSec() {
        return spreadSec;
    }

    public int getOvertimeSec() {
        return overtimeSec;
    }

    /** Work is the spread-over less the unpaid breaks, which is the figure the daily maximum limits. */
    public int getWorkSec() {
        return spreadSec - breakSec;
    }
}
