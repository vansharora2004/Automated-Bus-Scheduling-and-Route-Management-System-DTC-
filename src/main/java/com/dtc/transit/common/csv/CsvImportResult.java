package com.dtc.transit.common.csv;

import java.util.List;

/**
 * What a CSV import did, or would have done.
 *
 * <p>Errors are collected per row rather than thrown on the first one. A depot importing 5,000 buses
 * needs every problem in one pass; failing on row 12 and making them re-upload to find row 400 turns a
 * one-hour job into a week.
 *
 * @param dryRun       true when nothing was written, whatever the outcome
 * @param applied      true when rows were actually persisted
 * @param totalRows    data rows read, excluding the header
 * @param importedRows rows that would be, or were, written
 * @param errors       one entry per rejected row, in file order
 */
public record CsvImportResult(
        boolean dryRun, boolean applied, int totalRows, int importedRows, List<RowError> errors) {

    /**
     * @param line    1-based line number in the uploaded file, so it matches what a spreadsheet shows
     * @param field   the column at fault, or null when the problem is the row as a whole
     * @param message what is wrong, in terms the uploader can act on
     */
    public record RowError(long line, String field, String value, String message) {}

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** A validated run that wrote nothing, either because it was a dry run or because rows failed. */
    public static CsvImportResult validatedOnly(boolean dryRun, int totalRows, int validRows, List<RowError> errors) {
        return new CsvImportResult(dryRun, false, totalRows, validRows, errors);
    }

    public static CsvImportResult applied(int totalRows, int importedRows) {
        return new CsvImportResult(false, true, totalRows, importedRows, List.of());
    }
}
