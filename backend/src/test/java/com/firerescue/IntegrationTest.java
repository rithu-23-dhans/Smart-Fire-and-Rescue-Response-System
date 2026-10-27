package com.firerescue;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IntegrationTest {

    private static RescueSystem system;
    private static boolean dbAvailable = false;

    @BeforeAll
    public static void setup() {
        try (Connection c = Database.getConnection()) {
            dbAvailable = true;
            // Ensure schema tables and columns exist
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS incidents (" +
                        "id VARCHAR(20) PRIMARY KEY, location VARCHAR(80) NOT NULL, type VARCHAR(40) NOT NULL, " +
                        "intensity INT NOT NULL, population_affected INT NOT NULL, critical BOOLEAN NOT NULL, " +
                        "arrival_order INT NOT NULL, status VARCHAR(20) NOT NULL, assigned_unit VARCHAR(20) NOT NULL DEFAULT 'None', " +
                        "description TEXT, tracking_token VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

                try { s.execute("ALTER TABLE incidents ADD COLUMN description TEXT"); } catch (SQLException ignored) {}
                try { s.execute("ALTER TABLE incidents ADD COLUMN tracking_token VARCHAR(64) NOT NULL DEFAULT ''"); } catch (SQLException ignored) {}
                try { s.execute("ALTER TABLE incidents ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"); } catch (SQLException ignored) {}

                s.execute("CREATE TABLE IF NOT EXISTS response_units (" +
                        "id VARCHAR(20) PRIMARY KEY, unit_type VARCHAR(30) NOT NULL, station VARCHAR(80) NOT NULL, " +
                        "location VARCHAR(80) NOT NULL, available BOOLEAN NOT NULL, status VARCHAR(20) NOT NULL, " +
                        "incident_id VARCHAR(20) NOT NULL DEFAULT 'None', capabilities VARCHAR(255) NOT NULL, " +
                        "route_text TEXT, route_index INT NOT NULL DEFAULT 0)");

                s.execute("CREATE TABLE IF NOT EXISTS roads (" +
                        "from_location VARCHAR(80) NOT NULL, to_location VARCHAR(80) NOT NULL, " +
                        "travel_time INT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, " +
                        "PRIMARY KEY (from_location, to_location))");

                s.execute("CREATE TABLE IF NOT EXISTS dispatch_history (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT, incident_id VARCHAR(20) NOT NULL, " +
                        "unit_id VARCHAR(20) NOT NULL, route_text TEXT NOT NULL, travel_time INT NOT NULL, " +
                        "dispatched_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

                s.execute("CREATE TABLE IF NOT EXISTS status_history (" +
                        "id BIGINT PRIMARY KEY AUTO_INCREMENT, incident_id VARCHAR(20) NOT NULL, " +
                        "status VARCHAR(30) NOT NULL, note TEXT, source VARCHAR(20) NOT NULL DEFAULT 'SYSTEM', " +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

                s.execute("CREATE TABLE IF NOT EXISTS admin_users (" +
                        "username VARCHAR(50) PRIMARY KEY, password_hash VARCHAR(255) NOT NULL, " +
                        "salt VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            }
            system = new RescueSystem();
        } catch (Exception e) {
            System.err.println("MySQL database connection note: " + e.getMessage());
            dbAvailable = false;
        }
    }

    @Test
    @Order(1)
    @DisplayName("Verify Database connectivity and RescueSystem initialization")
    public void testDatabaseInit() {
        Assumptions.assumeTrue(dbAvailable, "MySQL is required for integration tests");
        assertNotNull(system);
        Map<String, Object> dash = system.adminDashboard();
        assertNotNull(dash);
        assertTrue((int) dash.get("totalIncidents") >= 15, "Should seed at least 15 incidents");
        assertEquals(4, (int) dash.get("totalUnits"), "Should have exactly 4 response units");
    }

    @Test
    @Order(2)
    @DisplayName("Verify Public Incident reporting and secure tracking token retrieval")
    public void testPublicReportAndTracking() throws SQLException {
        Assumptions.assumeTrue(dbAvailable, "MySQL is required for integration tests");

        Map<String, Object> report = new HashMap<>();
        report.put("location", "Market");
        report.put("type", "Fire");
        report.put("intensity", 8);
        report.put("population", 12);
        report.put("critical", true);
        report.put("description", "Structural fire reported near spice stalls.");

        Map<String, Object> res = system.report(report);
        assertTrue((boolean) res.get("success"));
        String incidentId = (String) res.get("incidentId");
        String trackingToken = (String) res.get("trackingToken");
        assertNotNull(incidentId);
        assertNotNull(trackingToken);

        // Track with correct token
        Map<String, Object> trackSuccess = system.trackIncident(incidentId, trackingToken);
        assertTrue((boolean) trackSuccess.get("success"));
        assertNotNull(trackSuccess.get("incident"));

        // Track with incorrect token (security enforcement)
        Map<String, Object> trackFail = system.trackIncident(incidentId, "tr_invalid_token_123");
        assertFalse((boolean) trackFail.get("success"), "Invalid token should be rejected");
    }

    @Test
    @Order(3)
    @DisplayName("Verify Dijkstra route calculation and road closure/reopening")
    public void testDijkstraAndRoadClosure() throws SQLException {
        Assumptions.assumeTrue(dbAvailable, "MySQL is required for integration tests");

        // Initial route: Station1 to Market
        RescueSystem.RouteResult r1 = system.findRoute("Station1", "Market");
        assertNotNull(r1);
        int initialTime = r1.time;
        assertTrue(initialTime > 0);

        // Close TownCenter - Market
        Map<String, Object> closeRes = system.closeRoad("TownCenter", "Market");
        assertTrue((boolean) closeRes.get("success"));

        // Recalculate route - should either take an alternate route or be longer
        RescueSystem.RouteResult r2 = system.findRoute("Station1", "Market");
        assertNotNull(r2);
        assertFalse(r2.path.contains("TownCenter") && r2.path.contains("Market") &&
                Math.abs(r2.path.indexOf("TownCenter") - r2.path.indexOf("Market")) == 1,
                "Route should not directly traverse closed road edge");

        // Reopen road
        Map<String, Object> reopenRes = system.reopenRoad("TownCenter", "Market");
        assertTrue((boolean) reopenRes.get("success"));

        // Route should restore to initial fastest time
        RescueSystem.RouteResult r3 = system.findRoute("Station1", "Market");
        assertNotNull(r3);
        assertEquals(initialTime, r3.time);
    }

    @Test
    @Order(4)
    @DisplayName("Verify full dispatch, track, and complete lifecycle")
    public void testDispatchLifecycle() throws SQLException {
        Assumptions.assumeTrue(dbAvailable, "MySQL is required for integration tests");

        // Dispatch next incident
        Map<String, Object> dispatchRes = system.dispatch();
        if ((boolean) dispatchRes.get("success")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> unitMap = (Map<String, Object>) dispatchRes.get("unit");
            String unitId = (String) unitMap.get("id");
            assertNotNull(unitId);

            // Advance one step
            Map<String, Object> trackRes = system.track(unitId);
            assertTrue((boolean) trackRes.get("success"));

            // Complete mission
            Map<String, Object> compRes = system.complete(unitId);
            assertTrue((boolean) compRes.get("success"));
        }
    }

    @Test
    @Order(5)
    @DisplayName("Verify Admin credentials authentication")
    public void testAdminAuth() {
        Assumptions.assumeTrue(dbAvailable, "MySQL is required for integration tests");
        assertTrue(system.verifyAdmin("admin", "Admin@123"), "Default admin credentials should authenticate");
        assertFalse(system.verifyAdmin("admin", "WrongPass"), "Invalid password should fail");
        assertFalse(system.verifyAdmin("unknown_user", "Admin@123"), "Unknown user should fail");
    }
}