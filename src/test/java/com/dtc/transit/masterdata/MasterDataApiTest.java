package com.dtc.transit.masterdata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.dtc.transit.support.SecurityWebTest;
import com.dtc.transit.user.Role;

/** Master-data CRUD over HTTP, including depot scoping and optimistic-locking fields. */
class MasterDataApiTest extends SecurityWebTest {

    @Test
    @DisplayName("an administrator can create a depot and read it back")
    void createAndReadDepot() {
        String admin = adminToken();

        ResponseEntity<String> created = post(
                "/api/v1/depots",
                admin,
                """
                {"code":"DPT-01","name":"Hari Nagar","longitude":77.1025,"latitude":28.6139,
                 "parkingCapacity":120,"chargingBays":6}""");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(support.field(created, "code")).isEqualTo("DPT-01");
        // Longitude round-trips in the order it was sent.
        assertThat(support.field(created, "longitude")).isEqualTo("77.1025");
        assertThat(support.field(created, "version")).isEqualTo("0");

        Long id = Long.valueOf(support.field(created, "id"));
        assertThat(get("/api/v1/depots/" + id, admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a duplicate depot code is refused, case-insensitively")
    void duplicateDepotCodeRefused() {
        String admin = adminToken();
        post("/api/v1/depots", admin, depotBody("DPT-02", "First"));

        ResponseEntity<String> second = post("/api/v1/depots", admin, depotBody("dpt-02", "Second"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(support.field(second, "code")).isEqualTo("DEPOT_CODE_TAKEN");
    }

    @Test
    @DisplayName("an out-of-range coordinate is refused")
    void outOfRangeCoordinateRefused() {
        String admin = adminToken();

        ResponseEntity<String> response = post(
                "/api/v1/depots",
                admin,
                """
                {"code":"DPT-03","name":"Impossible","longitude":77.1025,"latitude":100.0}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a Delhi lon/lat swap is accepted for now, because catching it needs Phase 4")
    void delhiSwapNotYetDetected() {
        String admin = adminToken();

        // 28E 77N is in Siberia, not Delhi, but both values are valid coordinates. Only the
        // service-area containment check in Phase 4 can reject this, so recording the current
        // behaviour is honest where asserting a rejection would not be.
        ResponseEntity<String> response = post(
                "/api/v1/depots",
                admin,
                """
                {"code":"DPT-03","name":"Swapped","longitude":28.6139,"latitude":77.1025}""");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a bus registration is normalised before the uniqueness check")
    void registrationIsNormalisedOnCreate() {
        String admin = adminToken();
        Long depotId = createDepot(admin, "DPT-10");

        ResponseEntity<String> created =
                post("/api/v1/buses", admin, busBody("dl-1pc-1234", "E-0421", depotId, "CNG", null));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(support.field(created, "registrationNo")).isEqualTo("DL1PC1234");
        // The value as typed survives for display.
        assertThat(support.field(created, "registrationNoAsEntered")).isEqualTo("dl-1pc-1234");

        // The same plate written differently is the same bus.
        ResponseEntity<String> duplicate =
                post("/api/v1/buses", admin, busBody("DL 1PC 1234", "E-0422", depotId, "CNG", null));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(support.field(duplicate, "code")).isEqualTo("REGISTRATION_TAKEN");
    }

    @Test
    @DisplayName("an electric bus without a range is refused")
    void electricBusNeedsRange() {
        String admin = adminToken();
        Long depotId = createDepot(admin, "DPT-11");

        ResponseEntity<String> response =
                post("/api/v1/buses", admin, busBody("DL1PE0001", "E-9001", depotId, "ELECTRIC", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("EV_RANGE_REQUIRED");
    }

    @Test
    @DisplayName("an electric bus with a range is accepted")
    void electricBusWithRangeAccepted() {
        String admin = adminToken();
        Long depotId = createDepot(admin, "DPT-12");

        ResponseEntity<String> response =
                post("/api/v1/buses", admin, busBody("DL1PE0002", "E-9002", depotId, "ELECTRIC", 180));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(support.field(response, "evRangeKm")).isEqualTo("180");
    }

    @Test
    @DisplayName("EC-SEC-01: another depot's bus reads as not found, not forbidden")
    void crossDepotBusIsNotFound() {
        String admin = adminToken();
        Long depotA = createDepot(admin, "DPT-A");
        Long depotB = createDepot(admin, "DPT-B");

        Long busInB = Long.valueOf(support.field(
                post("/api/v1/buses", admin, busBody("DL1PB0001", "B-1", depotB, "CNG", null)), "id"));

        // A scheduler bound to depot A.
        support.createUser("scoped-scheduler", depotA, Role.SCHEDULER);
        String scheduler = support.accessTokenFor(rest, "scoped-scheduler");

        ResponseEntity<String> response = get("/api/v1/buses/" + busInB, scheduler);

        // 403 would confirm the id exists and let a caller enumerate other depots' fleets by reading
        // status codes. 404 reveals only that there is nothing there for them.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a scheduler can read a bus in their own depot")
    void ownDepotBusIsVisible() {
        String admin = adminToken();
        Long depotA = createDepot(admin, "DPT-OWN");
        Long busInA = Long.valueOf(support.field(
                post("/api/v1/buses", admin, busBody("DL1POWN1", "O-1", depotA, "CNG", null)), "id"));

        support.createUser("own-scheduler", depotA, Role.SCHEDULER);
        String scheduler = support.accessTokenFor(rest, "own-scheduler");

        assertThat(get("/api/v1/buses/" + busInA, scheduler).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("EC-SEC-02: the bus list is narrowed to the caller's depot")
    void busListIsDepotScoped() {
        String admin = adminToken();
        Long depotA = createDepot(admin, "DPT-LA");
        Long depotB = createDepot(admin, "DPT-LB");
        post("/api/v1/buses", admin, busBody("DL1PLA001", "LA-1", depotA, "CNG", null));
        post("/api/v1/buses", admin, busBody("DL1PLB001", "LB-1", depotB, "CNG", null));

        support.createUser("list-scheduler", depotA, Role.SCHEDULER);
        String scheduler = support.accessTokenFor(rest, "list-scheduler");

        ResponseEntity<String> ownDepot = get("/api/v1/buses", scheduler);
        assertThat(support.longField(ownDepot, "totalElements")).isEqualTo(1);
        assertThat(ownDepot.getBody()).contains("DL1PLA001").doesNotContain("DL1PLB001");

        // Asking for the other depot explicitly returns nothing rather than someone else's fleet.
        ResponseEntity<String> otherDepot = get("/api/v1/buses?depotId=" + depotB, scheduler);
        assertThat(support.longField(otherDepot, "totalElements")).isZero();
    }

    @Test
    @DisplayName("an HQ administrator sees every depot's buses")
    void hqSeesEverything() {
        String admin = adminToken();
        Long depotA = createDepot(admin, "DPT-HA");
        Long depotB = createDepot(admin, "DPT-HB");
        post("/api/v1/buses", admin, busBody("DL1PHA001", "HA-1", depotA, "CNG", null));
        post("/api/v1/buses", admin, busBody("DL1PHB001", "HB-1", depotB, "CNG", null));

        assertThat(support.longField(get("/api/v1/buses", admin), "totalElements")).isEqualTo(2);
    }

    @Test
    @DisplayName("a driver must have a licence on record")
    void driverNeedsLicence() {
        String admin = adminToken();
        Long depotId = createDepot(admin, "DPT-20");

        ResponseEntity<String> response = post(
                "/api/v1/crew",
                admin,
                """
                {"employeeCode":"00123","name":"R Kumar","crewRole":"DRIVER","depotId":%d}"""
                        .formatted(depotId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(support.field(response, "code")).isEqualTo("LICENCE_REQUIRED");
    }

    @Test
    @DisplayName("a conductor needs no licence, and leading zeros in the code survive")
    void conductorNeedsNoLicenceAndCodeKeepsZeros() {
        String admin = adminToken();
        Long depotId = createDepot(admin, "DPT-21");

        ResponseEntity<String> response = post(
                "/api/v1/crew",
                admin,
                """
                {"employeeCode":"000456","name":"S Devi","crewRole":"CONDUCTOR","depotId":%d}"""
                        .formatted(depotId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Stored as text, so the zeros a spreadsheet would have eaten are still there.
        assertThat(support.field(response, "employeeCode")).isEqualTo("000456");
    }

    @Test
    @DisplayName("creating crew opens a depot posting, so past dates stay resolvable")
    void crewCreationOpensDepotHistory() {
        String admin = adminToken();
        Long depotId = createDepot(admin, "DPT-22");
        Long crewId = Long.valueOf(support.field(
                post(
                        "/api/v1/crew",
                        admin,
                        """
                        {"employeeCode":"000789","name":"A Singh","crewRole":"CONDUCTOR","depotId":%d,
                         "joinedOn":"2026-01-15"}"""
                                .formatted(depotId)),
                "id"));

        ResponseEntity<String> history = get("/api/v1/crew/" + crewId + "/depot-history", admin);

        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).contains("2026-01-15").contains(String.valueOf(depotId));
    }

    @Test
    @DisplayName("a transfer closes the previous posting rather than rewriting it")
    void transferAppendsHistory() {
        String admin = adminToken();
        Long from = createDepot(admin, "DPT-FROM");
        Long to = createDepot(admin, "DPT-TO");
        Long crewId = Long.valueOf(support.field(
                post(
                        "/api/v1/crew",
                        admin,
                        """
                        {"employeeCode":"001000","name":"T Transfer","crewRole":"CONDUCTOR","depotId":%d,
                         "joinedOn":"2026-01-01"}"""
                                .formatted(from)),
                "id"));

        ResponseEntity<String> transferred = post(
                "/api/v1/crew/" + crewId + "/transfer",
                admin,
                """
                {"depotId":%d,"effectiveFrom":"2026-03-01"}""".formatted(to));
        assertThat(transferred.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> history = get("/api/v1/crew/" + crewId + "/depot-history", admin);

        // Two postings, with the earlier one closed the day before the transfer. Overwriting the first
        // would make a past service date resolve to the wrong depot (edge case EC-CA-05).
        assertThat(history.getBody()).contains("2026-02-28").contains("2026-03-01");
    }

    @Test
    @DisplayName("a bus status change can record the window the bus is out for")
    void statusChangeRecordsUnavailability() {
        String admin = adminToken();
        Long depotId = createDepot(admin, "DPT-30");
        Long busId = Long.valueOf(support.field(
                post("/api/v1/buses", admin, busBody("DL1PS0001", "S-1", depotId, "CNG", null)), "id"));

        ResponseEntity<String> changed = support.call(
                rest,
                HttpMethod.PATCH,
                "/api/v1/buses/" + busId + "/status",
                admin,
                """
                {"status":"UNDER_MAINTENANCE","unavailableFrom":"2026-04-01T04:00:00Z",
                 "unavailableTo":"2026-04-01T09:00:00Z","note":"brake job"}""");

        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(support.field(changed, "status")).isEqualTo("UNDER_MAINTENANCE");

        ResponseEntity<String> windows = get("/api/v1/buses/" + busId + "/unavailability", admin);
        assertThat(windows.getBody()).contains("MAINTENANCE").contains("brake job");
    }

    @Test
    @DisplayName("stops can be filtered by radius, in metres")
    void stopsFilteredByRadius() {
        String admin = adminToken();
        support.createUser("stop-planner", null, Role.PLANNER);
        String planner = support.accessTokenFor(rest, "stop-planner");

        // Two stops about 1.1 km apart.
        post("/api/v1/stops", planner, stopBody("STP-NEAR", "Near", 77.2000, 28.6000));
        post("/api/v1/stops", planner, stopBody("STP-FAR", "Far", 77.2000, 28.6100));

        ResponseEntity<String> within500m = get("/api/v1/stops?near=77.2000,28.6000&radiusM=500", admin);

        assertThat(within500m.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(within500m.getBody()).contains("STP-NEAR").doesNotContain("STP-FAR");

        ResponseEntity<String> within5km = get("/api/v1/stops?near=77.2000,28.6000&radiusM=5000", admin);
        assertThat(within5km.getBody()).contains("STP-NEAR").contains("STP-FAR");
    }

    @Test
    @DisplayName("stops can be filtered by bounding box")
    void stopsFilteredByBoundingBox() {
        String admin = adminToken();
        support.createUser("bbox-planner", null, Role.PLANNER);
        String planner = support.accessTokenFor(rest, "bbox-planner");
        post("/api/v1/stops", planner, stopBody("STP-IN", "Inside", 77.2000, 28.6000));
        post("/api/v1/stops", planner, stopBody("STP-OUT", "Outside", 78.5000, 29.5000));

        ResponseEntity<String> response = get("/api/v1/stops?bbox=77.0,28.4,77.4,28.8", admin);

        assertThat(response.getBody()).contains("STP-IN").doesNotContain("STP-OUT");
    }

    @Test
    @DisplayName("a malformed bbox is rejected")
    void malformedBboxRejected() {
        ResponseEntity<String> response = get("/api/v1/stops?bbox=77.0,28.4,77.4", adminToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("an unknown enum value is rejected rather than ignored")
    void unknownEnumRejected() {
        ResponseEntity<String> response = get("/api/v1/buses?status=ACTVE", adminToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("crew can be filtered by licence expiry, excluding those with no licence")
    void licenceExpiryFilter() {
        String admin = adminToken();
        Long depotId = createDepot(admin, "DPT-40");
        post(
                "/api/v1/crew",
                admin,
                """
                {"employeeCode":"010001","name":"Expiring","crewRole":"DRIVER","depotId":%d,
                 "licenceNo":"L-1","licenceClass":"HPMV","licenceExpiry":"2026-02-01"}"""
                        .formatted(depotId));
        post(
                "/api/v1/crew",
                admin,
                """
                {"employeeCode":"010002","name":"Valid","crewRole":"DRIVER","depotId":%d,
                 "licenceNo":"L-2","licenceClass":"HPMV","licenceExpiry":"2030-02-01"}"""
                        .formatted(depotId));
        post(
                "/api/v1/crew",
                admin,
                """
                {"employeeCode":"010003","name":"Conductor","crewRole":"CONDUCTOR","depotId":%d}"""
                        .formatted(depotId));

        ResponseEntity<String> response = get("/api/v1/crew?licenceExpiringBefore=2027-01-01", admin);

        // The conductor has no licence to lapse and must not appear in a renewal list.
        assertThat(response.getBody()).contains("Expiring").doesNotContain("Valid").doesNotContain("Conductor");
    }

    private String adminToken() {
        support.createUser("md-admin", null, Role.ADMIN);
        return support.accessTokenFor(rest, "md-admin");
    }

    private Long createDepot(String token, String code) {
        return Long.valueOf(support.field(post("/api/v1/depots", token, depotBody(code, code + " depot")), "id"));
    }

    private static String depotBody(String code, String name) {
        return """
                {"code":"%s","name":"%s","longitude":77.1025,"latitude":28.6139,"chargingBays":4}"""
                .formatted(code, name);
    }

    private static String busBody(
            String registration, String fleetNo, Long depotId, String fuelType, Integer evRangeKm) {
        return """
                {"registrationNo":"%s","fleetNo":"%s","depotId":%d,"busType":"STANDARD",
                 "fuelType":"%s","capacity":40%s}"""
                .formatted(
                        registration,
                        fleetNo,
                        depotId,
                        fuelType,
                        evRangeKm == null ? "" : ",\"evRangeKm\":" + evRangeKm);
    }

    private static String stopBody(String code, String name, double lon, double lat) {
        return """
                {"code":"%s","name":"%s","longitude":%s,"latitude":%s,"terminal":true,"reliefPoint":true}"""
                .formatted(code, name, lon, lat);
    }

    private ResponseEntity<String> post(String path, String token, String body) {
        return support.call(rest, HttpMethod.POST, path, token, body);
    }

    private ResponseEntity<String> get(String path, String token) {
        return support.call(rest, HttpMethod.GET, path, token, null);
    }
}
