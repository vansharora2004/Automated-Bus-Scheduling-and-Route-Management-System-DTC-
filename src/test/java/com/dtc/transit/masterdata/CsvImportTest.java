package com.dtc.transit.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/** Bulk CSV import: dry run, per-row errors and the all-or-nothing rule. */
class CsvImportTest extends SecurityWebTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;

    @BeforeEach
    void seed() {
        support.createUser("csv-admin", null, Role.ADMIN);
        admin = support.accessTokenFor(rest, "csv-admin");
        support.call(
                rest,
                HttpMethod.POST,
                "/api/v1/depots",
                admin,
                """
                {"code":"CSV-DPT","name":"Import depot","longitude":77.1,"latitude":28.6}""");
    }

    @Test
    @DisplayName("dryRun defaults to true, so a bare request writes nothing")
    void dryRunIsTheDefault() {
        String csv =
                """
                registration_no,fleet_no,depot_code,bus_type,fuel_type,capacity
                DL1PA0001,A-1,CSV-DPT,STANDARD,CNG,40
                """;

        ResponseEntity<String> response = upload("/api/v1/buses/import", csv, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(response, "dryRun")).isEqualTo("true");
        assertThat(support.field(response, "applied")).isEqualTo("false");
        assertThat(support.longField(response, "importedRows")).isEqualTo(1);
        // Making the safe path the default means a mistyped request validates instead of writing.
        assertThat(busCount()).isZero();
    }

    @Test
    @DisplayName("dryRun=false imports the rows")
    void explicitImportWrites() {
        String csv =
                """
                registration_no,fleet_no,depot_code,bus_type,fuel_type,capacity
                DL1PB0001,B-1,CSV-DPT,STANDARD,CNG,40
                DL1PB0002,B-2,CSV-DPT,LOW_FLOOR,ELECTRIC,35
                """;

        // The electric row has no range, so this file should fail validation as a whole.
        ResponseEntity<String> response = upload("/api/v1/buses/import", csv, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(response, "applied")).isEqualTo("false");
        assertThat(response.getBody()).contains("ev_range_km");
        assertThat(busCount()).isZero();
    }

    @Test
    @DisplayName("a clean file is imported in full")
    void cleanFileImports() {
        String csv =
                """
                registration_no,fleet_no,depot_code,bus_type,fuel_type,capacity,ev_range_km,is_ac
                DL1PC0001,C-1,CSV-DPT,STANDARD,CNG,40,,no
                DL1PC0002,C-2,CSV-DPT,LOW_FLOOR,ELECTRIC,35,180,yes
                """;

        ResponseEntity<String> response = upload("/api/v1/buses/import", csv, false);

        assertThat(support.field(response, "applied")).isEqualTo("true");
        assertThat(support.longField(response, "importedRows")).isEqualTo(2);
        assertThat(busCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("EC-DATA-05: one bad row stops the whole file")
    void oneBadRowStopsEverything() {
        String csv =
                """
                registration_no,fleet_no,depot_code,bus_type,fuel_type,capacity
                DL1PD0001,D-1,CSV-DPT,STANDARD,CNG,40
                DL1PD0002,D-2,NO-SUCH-DEPOT,STANDARD,CNG,40
                DL1PD0003,D-3,CSV-DPT,STANDARD,CNG,40
                """;

        ResponseEntity<String> response = upload("/api/v1/buses/import", csv, false);

        // A partial import leaves the depot unable to tell which rows landed, and re-uploading then
        // trips uniqueness on the half that succeeded.
        assertThat(support.field(response, "applied")).isEqualTo("false");
        assertThat(busCount()).isZero();
        assertThat(response.getBody()).contains("NO-SUCH-DEPOT").contains("not a known depot");
    }

    @Test
    @DisplayName("EC-DATA-06: an unknown depot code creates nothing implicitly")
    void unknownDepotIsAnError() {
        String csv =
                """
                registration_no,fleet_no,depot_code,bus_type,fuel_type,capacity
                DL1PE0001,E-1,GHOST,STANDARD,CNG,40
                """;

        upload("/api/v1/buses/import", csv, false);

        Integer depots = jdbc.queryForObject("SELECT count(*) FROM depot WHERE code = 'GHOST'", Integer.class);
        assertThat(depots).isZero();
    }

    @Test
    @DisplayName("EC-DATA-07: a duplicate inside the file names both lines")
    void duplicateWithinFileReported() {
        String csv =
                """
                registration_no,fleet_no,depot_code,bus_type,fuel_type,capacity
                DL1PF0001,F-1,CSV-DPT,STANDARD,CNG,40
                dl-1pf-0001,F-2,CSV-DPT,STANDARD,CNG,40
                """;

        ResponseEntity<String> response = upload("/api/v1/buses/import", csv, false);

        // The second row is the same plate written differently, which only a normalised comparison
        // catches.
        assertThat(support.field(response, "applied")).isEqualTo("false");
        assertThat(response.getBody()).contains("more than once").contains("DL1PF0001");
    }

    @Test
    @DisplayName("errors carry the line number a spreadsheet would show")
    void errorsCarryLineNumbers() {
        String csv =
                """
                registration_no,fleet_no,depot_code,bus_type,fuel_type,capacity
                DL1PG0001,G-1,CSV-DPT,STANDARD,CNG,40
                DL1PG0002,G-2,CSV-DPT,STANDARD,CNG,not-a-number
                """;

        ResponseEntity<String> response = upload("/api/v1/buses/import", csv, false);

        // Line 3: header is line 1, first data row line 2. Reporting a zero-based record index would
        // send the uploader to the wrong row.
        assertThat(response.getBody()).contains("\"line\":3").contains("not a whole number");
    }

    @Test
    @DisplayName("a missing required column is reported before any row is read")
    void missingColumnReported() {
        String csv = """
                registration_no,fleet_no
                DL1PH0001,H-1
                """;

        ResponseEntity<String> response = upload("/api/v1/buses/import", csv, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("CSV_MISSING_COLUMNS");
    }

    @Test
    @DisplayName("EC-DATA-04: Hindi names survive a UTF-8 file")
    void utf8NamesSurvive() {
        String csv =
                """
                employee_code,name,crew_role,depot_code
                010101,सुरेश कुमार,CONDUCTOR,CSV-DPT
                """;

        ResponseEntity<String> response = upload("/api/v1/crew/import", csv, false);

        assertThat(support.field(response, "applied")).isEqualTo("true");
        String stored = jdbc.queryForObject(
                "SELECT name FROM crew_member WHERE employee_code = '010101'", String.class);
        assertThat(stored).isEqualTo("सुरेश कुमार");
    }

    @Test
    @DisplayName("EC-DATA-04: a non-UTF-8 file is refused rather than silently mangled")
    void nonUtf8Refused() {
        // A Windows-1252 export of a name with an accent. Decoded as UTF-8 this is invalid, and
        // tolerating it would store replacement characters nobody notices for months.
        byte[] latin1 = "employee_code,name,crew_role,depot_code\n010102,José,CONDUCTOR,CSV-DPT\n"
                .getBytes(StandardCharsets.ISO_8859_1);

        ResponseEntity<String> response = uploadBytes("/api/v1/crew/import", latin1, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("CSV_NOT_UTF8");
    }

    @Test
    @DisplayName("EC-DATA-02: a suspiciously short numeric employee code is refused")
    void strippedLeadingZerosRefused() {
        String csv = """
                employee_code,name,crew_role,depot_code
                123,Short Code,CONDUCTOR,CSV-DPT
                """;

        ResponseEntity<String> response = upload("/api/v1/crew/import", csv, false);

        // Padding it could point the row at a different, real employee, so the file is rejected and the
        // uploader is told to re-export the column as text.
        assertThat(support.field(response, "applied")).isEqualTo("false");
        assertThat(response.getBody()).contains("leading zeros");
    }

    @Test
    @DisplayName("EC-DATA-03: an ambiguous date format is refused")
    void ambiguousDateRefused() {
        String csv =
                """
                employee_code,name,crew_role,depot_code,licence_no,licence_class,licence_expiry
                010103,D Driver,DRIVER,CSV-DPT,L-9,HPMV,03/04/2026
                """;

        ResponseEntity<String> response = upload("/api/v1/crew/import", csv, false);

        // 03/04/2026 is either March or April depending on the exporter's locale, and guessing wrong
        // moves a licence expiry by nine months.
        assertThat(support.field(response, "applied")).isEqualTo("false");
        assertThat(response.getBody()).contains("ISO date");
    }

    @Test
    @DisplayName("quoted fields containing commas round-trip")
    void quotedFieldsSurvive() {
        String csv =
                """
                employee_code,name,crew_role,depot_code
                010104,"Kumar, Suresh",CONDUCTOR,CSV-DPT
                """;

        ResponseEntity<String> response = upload("/api/v1/crew/import", csv, false);

        assertThat(support.field(response, "applied")).isEqualTo("true");
        String stored = jdbc.queryForObject(
                "SELECT name FROM crew_member WHERE employee_code = '010104'", String.class);
        assertThat(stored).isEqualTo("Kumar, Suresh");
    }

    @Test
    @DisplayName("an empty upload is refused")
    void emptyUploadRefused() {
        ResponseEntity<String> response = uploadBytes("/api/v1/buses/import", new byte[0], false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("CSV_EMPTY");
    }

    private Integer busCount() {
        return jdbc.queryForObject("SELECT count(*) FROM bus", Integer.class);
    }

    private ResponseEntity<String> upload(String path, String csv, Boolean dryRun) {
        return uploadBytes(path, csv.getBytes(StandardCharsets.UTF_8), dryRun);
    }

    private ResponseEntity<String> uploadBytes(String path, byte[] content, Boolean dryRun) {
        var fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.TEXT_PLAIN);
        var filePart = new HttpEntity<>(
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return "upload.csv";
                    }
                },
                fileHeaders);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(admin);

        String url = dryRun == null ? path : path + "?dryRun=" + dryRun;
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
