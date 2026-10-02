package com.dtc.transit.masterdata.bus;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * Bulk bus import from a legacy spreadsheet export.
 *
 * <p>All or nothing per file. A partial import leaves the depot unable to tell which rows landed, and
 * re-uploading the same file then trips the uniqueness checks on the half that succeeded. Validating
 * everything first and writing only on a clean pass avoids that entirely (edge case EC-DATA-05).
 */
@Service
public class BusCsvImporter {

    private static final Set<String> REQUIRED_HEADERS =
            Set.of("registration_no", "fleet_no", "depot_code", "bus_type", "fuel_type", "capacity");

    private final BusRepository buses;
    private final DepotRepository depots;
    private final DepotAccessEvaluator depotAccess;
    private final ApplicationEventPublisher events;

    public BusCsvImporter(
            BusRepository buses,
            DepotRepository depots,
            DepotAccessEvaluator depotAccess,
            ApplicationEventPublisher events) {
        this.buses = buses;
        this.depots = depots;
        this.depotAccess = depotAccess;
        this.events = events;
    }

    /**
     * Validates and, unless this is a dry run, imports.
     *
     * @param dryRun when true nothing is written, which lets a depot check a file before committing to it
     */
    @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
    @Transactional
    public CsvImportResult importBuses(InputStream csv, boolean dryRun) {
        List<CSVRecord> records = CsvReader.read(csv, REQUIRED_HEADERS);
        List<CsvImportResult.RowError> errors = new ArrayList<>();
        List<Bus> pending = new ArrayList<>();

        // Duplicates inside the file are caught here, before the database sees them, so the report
        // names both offending lines rather than a constraint violation naming neither
        // (edge case EC-DATA-07).
        Set<String> seenRegistrations = new HashSet<>();

        for (CSVRecord record : records) {
            long line = record.getRecordNumber() + 1;
            try {
                pending.add(toBus(record, line, seenRegistrations, errors));
            } catch (RowRejected e) {
                // Already recorded in errors.
                continue;
            }
        }

        int valid = (int) pending.stream().filter(java.util.Objects::nonNull).count();

        if (dryRun || !errors.isEmpty()) {
            return CsvImportResult.validatedOnly(dryRun, records.size(), valid, errors);
        }

        pending.stream().filter(java.util.Objects::nonNull).forEach(buses::save);
        events.publishEvent(new AuditEvent(
                "BUS_CSV_IMPORTED",
                "BUS",
                "bulk",
                null,
                """
                {"rows":%d}""".formatted(valid),
                "CSV import"));
        return CsvImportResult.applied(records.size(), valid);
    }

    private Bus toBus(
            CSVRecord record, long line, Set<String> seenRegistrations, List<CsvImportResult.RowError> errors) {
        String registration = CsvReader.value(record, "registration_no");
        String fleetNo = CsvReader.value(record, "fleet_no");
        String depotCode = CsvReader.value(record, "depot_code");

        if (registration == null) {
            errors.add(new CsvImportResult.RowError(line, "registration_no", null, "is required"));
            throw new RowRejected();
        }

        String normalised;
        try {
            normalised = RegistrationNo.normalise(registration);
        } catch (IllegalArgumentException e) {
            errors.add(new CsvImportResult.RowError(line, "registration_no", registration, e.getMessage()));
            throw new RowRejected();
        }

        if (!seenRegistrations.add(normalised)) {
            errors.add(new CsvImportResult.RowError(
                    line,
                    "registration_no",
                    registration,
                    "appears more than once in this file (normalises to " + normalised + ")"));
            throw new RowRejected();
        }
        if (buses.existsByRegistrationNo(normalised)) {
            errors.add(new CsvImportResult.RowError(
                    line, "registration_no", registration, "already exists as " + normalised));
            throw new RowRejected();
        }
        if (fleetNo == null) {
            errors.add(new CsvImportResult.RowError(line, "fleet_no", null, "is required"));
            throw new RowRejected();
        }
        if (depotCode == null) {
            errors.add(new CsvImportResult.RowError(line, "depot_code", null, "is required"));
            throw new RowRejected();
        }

        // Nothing is created implicitly: an unknown depot code is a data error, not an invitation to
        // invent a depot (edge case EC-DATA-06).
        Depot depot = depots.findByCodeIgnoreCase(depotCode).orElse(null);
        if (depot == null) {
            errors.add(new CsvImportResult.RowError(line, "depot_code", depotCode, "is not a known depot"));
            throw new RowRejected();
        }
        if (!depotAccess.canAccess(depot.getId())) {
            errors.add(new CsvImportResult.RowError(
                    line, "depot_code", depotCode, "is outside the depots you may import for"));
            throw new RowRejected();
        }

        BusType busType = parseEnum(BusType.class, record, "bus_type", line, errors);
        FuelType fuelType = parseEnum(FuelType.class, record, "fuel_type", line, errors);
        Integer capacity = parseInt(record, "capacity", line, errors);
        Integer evRange = parseOptionalInt(record, "ev_range_km", line, errors);
        Boolean ac = parseOptionalBoolean(record, "is_ac", line, errors);

        if (busType == null || fuelType == null || capacity == null) {
            throw new RowRejected();
        }
        if (fuelType.requiresRange() && evRange == null) {
            errors.add(new CsvImportResult.RowError(
                    line, "ev_range_km", null, "is required for an electric bus"));
            throw new RowRejected();
        }

        return new Bus(
                registration,
                fleetNo,
                depot,
                busType,
                fuelType,
                Boolean.TRUE.equals(ac),
                capacity,
                evRange);
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, CSVRecord record, String column, long line, List<CsvImportResult.RowError> errors) {
        String raw = CsvReader.value(record, column);
        if (raw == null) {
            errors.add(new CsvImportResult.RowError(line, column, null, "is required"));
            return null;
        }
        try {
            return Enum.valueOf(type, raw.toUpperCase().replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            errors.add(new CsvImportResult.RowError(
                    line, column, raw, "must be one of " + java.util.Arrays.toString(type.getEnumConstants())));
            return null;
        }
    }

    private static Integer parseInt(
            CSVRecord record, String column, long line, List<CsvImportResult.RowError> errors) {
        String raw = CsvReader.value(record, column);
        if (raw == null) {
            errors.add(new CsvImportResult.RowError(line, column, null, "is required"));
            return null;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                errors.add(new CsvImportResult.RowError(line, column, raw, "must be greater than zero"));
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            errors.add(new CsvImportResult.RowError(line, column, raw, "is not a whole number"));
            return null;
        }
    }

    private static Integer parseOptionalInt(
            CSVRecord record, String column, long line, List<CsvImportResult.RowError> errors) {
        String raw = CsvReader.value(record, column);
        if (raw == null) {
            return null;
        }
        return parseInt(record, column, line, errors);
    }

    private static Boolean parseOptionalBoolean(
            CSVRecord record, String column, long line, List<CsvImportResult.RowError> errors) {
        String raw = CsvReader.value(record, column);
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase()) {
            case "true", "yes", "y", "1" -> true;
            case "false", "no", "n", "0" -> false;
            default -> {
                errors.add(new CsvImportResult.RowError(line, column, raw, "must be true or false"));
                yield null;
            }
        };
    }

    /** Signals that the current row was rejected and its error already recorded. */
    private static final class RowRejected extends RuntimeException {
        RowRejected() {
            super(null, null, false, false);
        }
    }
}
