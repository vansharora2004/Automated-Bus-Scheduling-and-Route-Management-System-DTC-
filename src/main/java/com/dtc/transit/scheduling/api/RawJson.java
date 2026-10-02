package com.dtc.transit.scheduling.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Turns a stored JSONB column into a node the response can embed.
 *
 * <p>Without this the column would be rendered as a quoted string, and a client would have to parse JSON out of a
 * JSON string. Metrics and conflict references are objects in the database and should be objects in the response.
 */
final class RawJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RawJson() {
        // static helper
    }

    /**
     * Parses stored JSON, or returns null when the column is empty.
     *
     * <p>Unparseable JSON returns null rather than failing the request. The column is written by this application,
     * so a bad value is a bug worth finding, but failing a read of an otherwise perfectly good schedule over its
     * metrics blob would be the wrong trade.
     */
    static JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
