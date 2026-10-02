package com.dtc.transit.masterdata.crew;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.csv.CSVRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dtc.transit.common.audit.AuditEvent;
import com.dtc.transit.common.csv.CsvImportResult;
import com.dtc.transit.common.csv.CsvReader;
import com.dtc.transit.masterdata.depot.Depot;
import com.dtc.transit.masterdata.depot.DepotRepository;
import com.dtc.transit.security.DepotAccessEvaluator;

/**
 * Bulk crew import from a legacy spreadsheet export.
 *
 * <p>All or nothing per file, for the same reason as the bus importer.
 */
@Service
public class CrewCsvImporter {

    private static final Set<String> REQUIRED_HEADERS =
            Set.of("employee_code", "name", "crew_role", "depot_code");

    /** Length an employee code should have, used only to warn about Excel stripping leading zeros. */
    private static final int EXPECTED_CODE_LENGTH = 5;

    private final CrewMemberRepository crew;
    private final CrewDepotHistoryRepository depotHistory;
    private final DepotRepository depots;
    private final DepotAccessEvaluator depotAccess;
    private final ApplicationEventPublisher events;

    public CrewCsvImporter(
            CrewMemberRepository crew,
            CrewDepotHistoryRepository depotHistory,
            DepotRepository depots,
            DepotAccessEvaluator depotAccess,
            ApplicationEventPublisher events) {
        this.crew = crew;
        this.depotHistory = depotHistory;
        this.depots = depots;
        this.depotAccess = depotAccess;
        this.events = events;
    }

    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public CsvImportResult importCrew(InputStream csv, boolean dryRun) {
        List<CSVRecord> records = CsvReader.read(csv, REQUIRED_HEADERS);
        List<CsvImportResult.RowError> errors = new ArrayList<>();
        List<CrewMember> pending = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        for (CSVRecord record : records) {
            long line = record.getRecordNumber() + 1;
            CrewMember member = toCrewMember(record, line, seenCodes, errors);
            if (member != null) {
                pending.add(member);
            }
        }

        int valid = (int) pending.stream().filter(Objects::nonNull).count();

        if (dryRun || !errors.isEmpty()) {
            return CsvImportResult.validatedOnly(dryRun, records.size(), valid, errors);
        }

        for (CrewMember member : pending) {
            crew.save(member);
            depotHistory.save(
                    new CrewDepotHistory(member.getId(), member.getDepot().getId(), LocalDate.now()));
        }
        events.publishEvent(new AuditEvent(
                "CREW_CSV_IMPORTED",
                "CREW_MEMBER",
                "bulk",
                null,
                """
                {"rows":%d}""".formatted(valid),
                "CSV import"));
        return CsvImportResult.applied(records.size(), valid);
    }

    private CrewMember toCrewMember(
            CSVRecord record, long line, Set<String> seenCodes, List<CsvImportResult.RowError> errors) {
        String code = CsvReader.value(record, "employee_code");
        String name = CsvReader.value(record, "name");
        String depotCode = CsvReader.value(record, "depot_code");

        if (code == null) {
            errors.add(new CsvImportResult.RowError(line, "employee_code", null, "is required"));
            return null;
        }
        // A code shorter than expected and entirely numeric is the classic sign of a spreadsheet having
        // dropped leading zeros. Rejected rather than guessed at, because padding it could point the row
        // at a different, real employee (edge case EC-DATA-02).
        if (code.length() < EXPECTED_CODE_LENGTH && code.chars().allMatch(Character::isDigit)) {
            errors.add(new CsvImportResult.RowError(
                    line,
                    "employee_code",
                    code,
                    "is shorter than " + EXPECTED_CODE_LENGTH
                            + " digits; leading zeros may have been stripped by a spreadsheet. "
                            + "Re-export the column as text."));
            return null;
        }
        if (!seenCodes.add(code.toUpperCase())) {
            errors.add(new CsvImportResult.RowError(
                    line, "employee_code", code, "appears more than once in this file"));
            return null;
        }
        if (crew.existsByEmployeeCodeIgnoreCase(code)) {
            errors.add(new CsvImportResult.RowError(line, "employee_code", code, "already exists"));
            return null;
        }
        if (name == null) {
            errors.add(new CsvImportResult.RowError(line, "name", null, "is required"));
            return null;
        }
        if (depotCode == null) {
            errors.add(new CsvImportResult.RowError(line, "depot_code", null, "is required"));
            return null;
        }

        Depot depot = depots.findByCodeIgnoreCase(depotCode).orElse(null);
        if (depot == null) {
            errors.add(new CsvImportResult.RowError(line, "depot_code", depotCode, "is not a known depot"));
            return null;
        }
        if (!depotAccess.canAccess(depot.getId())) {
            errors.add(new CsvImportResult.RowError(
                    line, "depot_code", depotCode, "is outside the depots you may import for"));
            return null;
        }

        CrewRole role;
        String rawRole = CsvReader.value(record, "crew_role");
        try {
            role = CrewRole.valueOf(Objects.requireNonNull(rawRole).toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            errors.add(new CsvImportResult.RowError(
                    line, "crew_role", rawRole, "must be DRIVER or CONDUCTOR"));
            return null;
        }

        LicenceClass licenceClass = null;
        String rawClass = CsvReader.value(record, "licence_class");
        if (rawClass != null) {
            try {
                licenceClass = LicenceClass.valueOf(rawClass.toUpperCase());
            } catch (IllegalArgumentException e) {
                errors.add(new CsvImportResult.RowError(
                        line, "licence_class", rawClass, "must be LMV, HMV or HPMV"));
                return null;
            }
        }

        LocalDate licenceExpiry = null;
        String rawExpiry = CsvReader.value(record, "licence_expiry");
        if (rawExpiry != null) {
            try {
                // ISO only. An ambiguous value such as 03/04/2026 cannot be resolved without knowing the
                // exporter's locale, and guessing wrong moves an expiry by nine months
                // (edge case EC-DATA-03).
                licenceExpiry = LocalDate.parse(rawExpiry);
            } catch (DateTimeParseException e) {
                errors.add(new CsvImportResult.RowError(
                        line, "licence_expiry", rawExpiry, "must be an ISO date such as 2026-03-14"));
                return null;
            }
        }

        String licenceNo = CsvReader.value(record, "licence_no");
        if (role.requiresLicence() && (licenceNo == null || licenceExpiry == null)) {
            errors.add(new CsvImportResult.RowError(
                    line, "licence_no", licenceNo, "a driver needs both a licence number and an expiry date"));
            return null;
        }

        Integer weeklyOff = null;
        String rawWeeklyOff = CsvReader.value(record, "weekly_off_dow");
        if (rawWeeklyOff != null) {
            try {
                weeklyOff = Integer.valueOf(rawWeeklyOff);
                if (weeklyOff < 1 || weeklyOff > 7) {
                    errors.add(new CsvImportResult.RowError(
                            line, "weekly_off_dow", rawWeeklyOff, "must be 1 (Monday) to 7 (Sunday)"));
                    return null;
                }
            } catch (NumberFormatException e) {
                errors.add(new CsvImportResult.RowError(
                        line, "weekly_off_dow", rawWeeklyOff, "must be a number from 1 to 7"));
                return null;
            }
        }

        return new CrewMember(code, name, role, depot, licenceNo, licenceClass, licenceExpiry, weeklyOff);
    }
}
