package com.dtc.transit.common.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.dtc.transit.common.error.BusinessRuleException;

/** Reads an uploaded CSV into records, with the encoding and header rules the importers rely on. */
public final class CsvReader {

    /** UTF-8 byte order mark, which Excel writes and which would otherwise corrupt the first header. */
    private static final String BOM = "﻿";

    private CsvReader() {}

    /**
     * Parses the stream, requiring UTF-8 and the given header columns.
     *
     * <p>Encoding is strict rather than lenient. A Windows-1252 export silently decoded as UTF-8 turns
     * Hindi names into replacement characters, and the damage is invisible until someone reads the row
     * months later (edge case EC-DATA-04).
     *
     * @throws BusinessRuleException if the file is not valid UTF-8 or a required column is missing
     */
    public static List<CSVRecord> read(InputStream input, Set<String> requiredHeaders) {
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);

        try (var reader = new BufferedReader(new InputStreamReader(input, decoder))) {
            var format = CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreSurroundingSpaces(true)
                    .setIgnoreEmptyLines(true)
                    .setTrim(true)
                    .get();

            try (CSVParser parser = format.parse(reader)) {
                List<String> headers = parser.getHeaderNames().stream()
                        .map(CsvReader::stripBom)
                        .toList();
                List<String> missing = requiredHeaders.stream()
                        .filter(required -> headers.stream().noneMatch(required::equalsIgnoreCase))
                        .sorted()
                        .toList();
                if (!missing.isEmpty()) {
                    throw new BusinessRuleException(
                            "CSV_MISSING_COLUMNS",
                            "Missing required column(s) " + missing + ". Found: " + headers);
                }
                return new ArrayList<>(parser.getRecords());
            }
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new BusinessRuleException(
                    "CSV_NOT_UTF8",
                    "The file is not valid UTF-8. Re-export it as UTF-8 (CSV UTF-8 in Excel).");
        } catch (IOException e) {
            throw new BusinessRuleException("CSV_UNREADABLE", "The file could not be read: " + e.getMessage());
        }
    }

    /** Reads a column, returning null for an absent or blank value. */
    public static String value(CSVRecord record, String column) {
        if (!record.isMapped(column)) {
            return null;
        }
        String raw = record.get(column);
        if (raw == null) {
            return null;
        }
        String trimmed = stripBom(raw).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String stripBom(String value) {
        return value.startsWith(BOM) ? value.substring(1) : value;
    }
}
