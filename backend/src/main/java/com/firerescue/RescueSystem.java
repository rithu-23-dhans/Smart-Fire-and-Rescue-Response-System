package com.firerescue;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.sql.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Smart Fire and Rescue Response System core.
 * Scenario: Town of Sudar (2 Stations, 4 Units, 12 Road Locations).
 *
 * Preserves the exact priority formula:
 * 1. Higher intensity first
 * 2. Critical infrastructure before non-critical
 * 3. Higher population-exposure rank first
 * 4. Earlier arrival order first (FIFO)
 *
 * Dijkstra shortest travel time; compatible nearest available unit.
 */
public final class RescueSystem {

    // =========================================================================
    // 1. OBJECT-ORIENTED UNIT HIERARCHY
    // =========================================================================

    public abstract static class ResponseUnit {
        public String id, type, station, location, status, incidentId;
        public boolean available;
        public Set<String> capabilities = new HashSet<>();
        public List<String> route = new ArrayList<>();
        public int routeIndex;

        public ResponseUnit(String id, String type, String station, String location, String... capabilities) {
            this.id = id;
            this.type = type;
            this.station = station;
            this.location = location;
            this.available = true;
            this.status = "Available";
            this.incidentId = "None";
            if (capabilities != null) {
                this.capabilities.addAll(Arrays.asList(capabilities));
            }
        }

        public boolean canHandle(String incidentType) {
            return capabilities.contains(incidentType);
        }

        public void assign(String incidentId, List<String> route) {
            this.available = false;
            this.status = "En Route";
            this.incidentId = incidentId;
            this.route = new ArrayList<>(route);
            this.routeIndex = 0;
        }

        public void complete() {
            this.available = true;
            this.status = "Available";
            this.incidentId = "None";
            this.route.clear();
            this.routeIndex = 0;
        }

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("type", type);
            m.put("station", station);
            m.put("location", location);
            m.put("available", available);
            m.put("status", status);
            m.put("incidentId", incidentId);
            m.put("capabilities", new ArrayList<>(capabilities));
            m.put("route", route);
            m.put("routeIndex", routeIndex);
            return m;
        }
    }

    public static final class FireEngine extends ResponseUnit {
        public FireEngine(String id, String station, String location, String... extraCapabilities) {
            super(id, "Fire Engine", station, location, "Fire");
            if (extraCapabilities != null) {
                this.capabilities.addAll(Arrays.asList(extraCapabilities));
            }
        }
    }

    public static final class RescueVan extends ResponseUnit {
        public RescueVan(String id, String station, String location, String... extraCapabilities) {
            super(id, "Rescue Van", station, location, "Rescue", "Accident");
            if (extraCapabilities != null) {
                this.capabilities.addAll(Arrays.asList(extraCapabilities));
            }
        }
    }

    // =========================================================================
    // 2. INCIDENT MODEL
    // =========================================================================

    public static final class Incident {
        public String id, location, type, status, assignedUnit, description, trackingToken;
        public int intensity, population, arrivalOrder;
        public boolean critical;
        public Timestamp createdAt;

        public Incident(String id, String location, String type, int intensity, int population,
                        boolean critical, int arrivalOrder, String status, String assignedUnit,
                        String description, String trackingToken, Timestamp createdAt) {
            this.id = id;
            this.location = location;
            this.type = type;
            this.intensity = intensity;
            this.population = population;
            this.critical = critical;
            this.arrivalOrder = arrivalOrder;
            this.status = status;
            this.assignedUnit = assignedUnit;
            this.description = (description == null || description.isBlank()) ? "No description provided." : description;
            this.trackingToken = trackingToken;
            this.createdAt = createdAt == null ? new Timestamp(System.currentTimeMillis()) : createdAt;
        }

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("location", location);
            m.put("type", type);
            m.put("intensity", intensity);
            m.put("population", population);
            m.put("critical", critical);
            m.put("arrivalOrder", arrivalOrder);
            m.put("status", status);
            m.put("assignedUnit", assignedUnit);
            m.put("description", description);
            m.put("trackingToken", trackingToken);
            m.put("createdAt", createdAt.toString());
            return m;
        }

        public Map<String, Object> publicJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("location", location);
            m.put("type", type);
            m.put("intensity", intensity);
            m.put("population", population);
            m.put("critical", critical);
            m.put("status", status);
            m.put("assignedUnit", assignedUnit);
            m.put("description", description);
            m.put("createdAt", createdAt.toString());
            return m;
        }
    }

    // =========================================================================
    // 3. ROAD & ROUTING MODELS
    // =========================================================================

    public static final class RoadEdge {
        public String from, to;
        public int time;
        public boolean active;

        public RoadEdge(String from, String to, int time, boolean active) {
            this.from = from;
            this.to = to;
            this.time = time;
            this.active = active;
        }

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", from);
            m.put("to", to);
            m.put("time", time);
            m.put("active", active);
            return m;
        }
    }

    private static final class RouteState {
        String location;
        int distance;
        RouteState(String location, int distance) {
            this.location = location;
            this.distance = distance;
        }
    }

    public static final class RouteResult {
        public List<String> path;
        public int time;

        public RouteResult(List<String> path, int time) {
            this.path = path;
            this.time = time;
        }

        public Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("time", time);
            return m;
        }
    }

    // =========================================================================
    // 4. PRIORITY COMPARATOR (Exact Preservation)
    // =========================================================================

    public static final Comparator<Incident> PRIORITY = (a, b) -> {
        if (a.intensity != b.intensity) return Integer.compare(b.intensity, a.intensity);
        if (a.critical != b.critical) return a.critical ? -1 : 1;
        if (a.population != b.population) return Integer.compare(b.population, a.population);
        return Integer.compare(a.arrivalOrder, b.arrivalOrder);
    };

    // State collections
    private final Map<String, ResponseUnit> units = new LinkedHashMap<>();
    private final Map<String, Incident> incidents = new LinkedHashMap<>();
    private final Map<String, List<RoadEdge>> roadGraph = new LinkedHashMap<>();
    private final PriorityQueue<Incident> queue = new PriorityQueue<>(PRIORITY);
    private int counter = 0;
    private static final SecureRandom RANDOM = new SecureRandom();

    public RescueSystem() throws SQLException {
        loadOrSeed();
        initAdminIfMissing();
    }

    // =========================================================================
    // 5. GRAPH & SEEDING LOGIC
    // =========================================================================

    private void addLocation(String l) {
        roadGraph.putIfAbsent(l, new ArrayList<>());
    }

    private void addRoadMemory(String a, String b, int t, boolean active) {
        addLocation(a);
        addLocation(b);
        roadGraph.get(a).removeIf(e -> e.to.equals(b));
        roadGraph.get(b).removeIf(e -> e.to.equals(a));
        roadGraph.get(a).add(new RoadEdge(a, b, t, active));
        roadGraph.get(b).add(new RoadEdge(b, a, t, active));
    }

    private void seedRoads() {
        addRoadMemory("Station1", "TownCenter", 4, true);
        addRoadMemory("Station1", "ResidentialArea", 6, true);
        addRoadMemory("Station1", "IndustrialArea", 8, true);
        addRoadMemory("Station2", "TownCenter", 5, true);
        addRoadMemory("Station2", "Hospital", 4, true);
        addRoadMemory("Station2", "School", 7, true);
        addRoadMemory("TownCenter", "Market", 3, true);
        addRoadMemory("TownCenter", "Hospital", 5, true);
        addRoadMemory("TownCenter", "School", 6, true);
        addRoadMemory("Market", "ResidentialArea", 4, true);
        addRoadMemory("Market", "School", 3, true);
        addRoadMemory("ResidentialArea", "RiverBridge", 5, true);
        addRoadMemory("RiverBridge", "IndustrialArea", 4, true);
        addRoadMemory("IndustrialArea", "Warehouse", 3, true);
        addRoadMemory("Hospital", "School", 4, true);
        addRoadMemory("Warehouse", "AirportRoad", 6, true);
        addRoadMemory("AirportRoad", "RiverBridge", 5, true);
        addRoadMemory("School", "OldTown", 4, true);
        addRoadMemory("OldTown", "Market", 5, true);
        addRoadMemory("Hospital", "OldTown", 6, true);
    }

    private void seedUnits() {
        units.put("FE01", new FireEngine("FE01", "Station1", "Station1", "Industrial Fire"));
        units.put("FE02", new FireEngine("FE02", "Station1", "Station1"));
        units.put("RV01", new RescueVan("RV01", "Station2", "Station2"));
        units.put("RV02", new RescueVan("RV02", "Station2", "Station2"));
    }

    public static String generateToken() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("tr_");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void addSample(String location, String type, int intensity, int population, boolean critical, String desc) {
        counter++;
        String id = "I" + String.format("%02d", counter);
        Incident i = new Incident(id, location, type, intensity, population, critical, counter,
                "Pending", "None", desc, generateToken(), new Timestamp(System.currentTimeMillis()));
        incidents.put(i.id, i);
        queue.add(i);
    }

    private void seedIncidents() {
        addSample("ResidentialArea", "Fire", 8, 7, false, "Apartment kitchen fire spreading to roof.");
        addSample("Hospital", "Rescue", 10, 10, true, "Power failure in ICU wing; backup evacuation required.");
        addSample("IndustrialArea", "Industrial Fire", 9, 8, true, "Chemical storage warehouse fire.");
        addSample("School", "Rescue", 7, 9, true, "Lab smoke detection during active session.");
        addSample("Market", "Fire", 6, 6, false, "Stall electrical malfunction in open market.");
        addSample("Warehouse", "Industrial Fire", 8, 5, true, "Flammable solvent container blaze.");
        addSample("OldTown", "Accident", 6, 4, false, "Two-vehicle collision near heritage archway.");
        addSample("RiverBridge", "Rescue", 9, 7, true, "Cargo truck hanging partially over bridge barrier.");
        addSample("AirportRoad", "Accident", 7, 5, false, "Bus collision blocking airport junction.");
        addSample("TownCenter", "Fire", 5, 3, false, "Trash compact fire behind shopping complex.");
        addSample("ResidentialArea", "Rescue", 7, 6, false, "Senior citizen trapped in elevator.");
        addSample("School", "Fire", 8, 8, true, "Cafeteria fire near primary wing.");
        addSample("Market", "Accident", 5, 2, false, "Delivery scooter skidded on oil spill.");
        addSample("Hospital", "Fire", 9, 9, true, "Basement laundry fire threatening main supply lines.");
        addSample("IndustrialArea", "Fire", 7, 6, true, "Transformer spark at metal fabricating plant.");
    }

    // =========================================================================
    // 6. PERSISTENCE & DATABASE SYNCHRONIZATION
    // =========================================================================

    private void ensureSchema(Connection c) {
        try (Statement s = c.createStatement()) {
            try { s.execute("ALTER TABLE incidents ADD COLUMN description TEXT"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE incidents ADD COLUMN tracking_token VARCHAR(64) NOT NULL DEFAULT ''"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE incidents ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"); } catch (SQLException ignored) {}
            try { s.execute("CREATE TABLE IF NOT EXISTS status_history (" +
                    "id BIGINT PRIMARY KEY AUTO_INCREMENT, incident_id VARCHAR(20) NOT NULL, " +
                    "status VARCHAR(30) NOT NULL, note TEXT, source VARCHAR(20) NOT NULL DEFAULT 'SYSTEM', " +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, INDEX idx_incident_id (incident_id))"); } catch (SQLException ignored) {}
            try { s.execute("CREATE TABLE IF NOT EXISTS admin_users (" +
                    "username VARCHAR(50) PRIMARY KEY, password_hash VARCHAR(255) NOT NULL, " +
                    "salt VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"); } catch (SQLException ignored) {}
        } catch (Exception ignored) {}
    }

    private void loadOrSeed() throws SQLException {
        try (Connection c = Database.getConnection()) {
            ensureSchema(c);

            boolean hasIncidents;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM incidents")) {
                r.next();
                hasIncidents = r.getInt(1) > 0;
            }

            if (!hasIncidents) {
                seedRoads();
                seedUnits();
                seedIncidents();
                persistAll();
                try (Statement s = c.createStatement()) {
                    for (Incident i : incidents.values()) {
                        logStatus(c, i.id, "Pending", "Incident reported and queued for response.", "SYSTEM");
                    }
                }
                return;
            }

            // Load roads
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM roads")) {
                while (r.next()) {
                    addRoadMemory(r.getString("from_location"), r.getString("to_location"),
                            r.getInt("travel_time"), r.getBoolean("active"));
                }
            }

            // Load units
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM response_units")) {
                while (r.next()) {
                    String id = r.getString("id");
                    String type = r.getString("unit_type");
                    String station = r.getString("station");
                    String loc = r.getString("location");

                    ResponseUnit u;
                    if ("Fire Engine".equalsIgnoreCase(type)) {
                        u = new FireEngine(id, station, loc);
                    } else {
                        u = new RescueVan(id, station, loc);
                    }

                    String caps = r.getString("capabilities");
                    if (caps != null && !caps.isBlank()) {
                        u.capabilities.addAll(Arrays.asList(caps.split(",")));
                    }
                    u.available = r.getBoolean("available");
                    u.status = r.getString("status");
                    u.incidentId = r.getString("incident_id");
                    u.route = splitRoute(r.getString("route_text"));
                    u.routeIndex = r.getInt("route_index");
                    units.put(u.id, u);
                }
            }

            // Load incidents
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM incidents ORDER BY arrival_order ASC")) {
                while (r.next()) {
                    String desc = "";
                    try { desc = r.getString("description"); } catch (SQLException ignored) {}
                    String token = "";
                    try { token = r.getString("tracking_token"); } catch (SQLException ignored) {}
                    if (token == null || token.isBlank()) token = generateToken();
                    Timestamp ts = null;
                    try { ts = r.getTimestamp("created_at"); } catch (SQLException ignored) {}

                    Incident i = new Incident(
                            r.getString("id"),
                            r.getString("location"),
                            r.getString("type"),
                            r.getInt("intensity"),
                            r.getInt("population_affected"),
                            r.getBoolean("critical"),
                            r.getInt("arrival_order"),
                            r.getString("status"),
                            r.getString("assigned_unit"),
                            desc,
                            token,
                            ts
                    );
                    incidents.put(i.id, i);
                    counter = Math.max(counter, i.arrivalOrder);
                    if ("Pending".equals(i.status)) {
                        queue.add(i);
                    }
                }
            }
        }
    }

    private List<String> splitRoute(String route) {
        if (route == null || route.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(route.split(" -> ", -1)));
    }

    private void persistAll() throws SQLException {
        try (Connection c = Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM incidents");
                    s.executeUpdate("DELETE FROM response_units");
                    s.executeUpdate("DELETE FROM roads");
                }

                // Persist incidents
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO incidents(id,location,type,intensity,population_affected,critical,arrival_order,status,assigned_unit,description,tracking_token,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (Incident i : incidents.values()) {
                        p.setString(1, i.id);
                        p.setString(2, i.location);
                        p.setString(3, i.type);
                        p.setInt(4, i.intensity);
                        p.setInt(5, i.population);
                        p.setBoolean(6, i.critical);
                        p.setInt(7, i.arrivalOrder);
                        p.setString(8, i.status);
                        p.setString(9, i.assignedUnit);
                        p.setString(10, i.description);
                        p.setString(11, i.trackingToken);
                        p.setTimestamp(12, i.createdAt);
                        p.addBatch();
                    }
                    p.executeBatch();
                }

                // Persist response units
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO response_units(id,unit_type,station,location,available,status,incident_id,capabilities,route_text,route_index) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    for (ResponseUnit u : units.values()) {
                        p.setString(1, u.id);
                        p.setString(2, u.type);
                        p.setString(3, u.station);
                        p.setString(4, u.location);
                        p.setBoolean(5, u.available);
                        p.setString(6, u.status);
                        p.setString(7, u.incidentId);
                        p.setString(8, String.join(",", u.capabilities));
                        p.setString(9, String.join(" -> ", u.route));
                        p.setInt(10, u.routeIndex);
                        p.addBatch();
                    }
                    p.executeBatch();
                }

                // Persist roads (store undirected edge once)
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO roads(from_location,to_location,travel_time,active) VALUES(?,?,?,?)")) {
                    Set<String> seen = new HashSet<>();
                    for (Map.Entry<String, List<RoadEdge>> e : roadGraph.entrySet()) {
                        for (RoadEdge r : e.getValue()) {
                            String key = e.getKey().compareTo(r.to) < 0 ? (e.getKey() + "||" + r.to) : (r.to + "||" + e.getKey());
                            if (seen.add(key)) {
                                String from = e.getKey().compareTo(r.to) < 0 ? e.getKey() : r.to;
                                String to = e.getKey().compareTo(r.to) < 0 ? r.to : e.getKey();
                                p.setString(1, from);
                                p.setString(2, to);
                                p.setInt(3, r.time);
                                p.setBoolean(4, r.active);
                                p.addBatch();
                            }
                        }
                    }
                    p.executeBatch();
                }

                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public void logStatus(Connection c, String incidentId, String status, String note, String source) {
        String sql = "INSERT INTO status_history(incident_id, status, note, source) VALUES(?,?,?,?)";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, incidentId);
            p.setString(2, status);
            p.setString(3, note);
            p.setString(4, source);
            p.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to log status: " + e.getMessage());
        }
    }

    // =========================================================================
    // 7. DIJKSTRA PATHFINDING (Active Road Graph)
    // =========================================================================

    public RouteResult findRoute(String start, String destination) {
        if (!roadGraph.containsKey(start) || !roadGraph.containsKey(destination)) return null;
        if (start.equals(destination)) {
            return new RouteResult(Collections.singletonList(start), 0);
        }

        Map<String, Integer> distance = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        for (String l : roadGraph.keySet()) {
            distance.put(l, Integer.MAX_VALUE);
        }
        distance.put(start, 0);

        PriorityQueue<RouteState> pq = new PriorityQueue<>(Comparator.comparingInt(s -> s.distance));
        pq.add(new RouteState(start, 0));

        while (!pq.isEmpty()) {
            RouteState cur = pq.poll();
            if (cur.distance != distance.get(cur.location)) continue;
            if (cur.location.equals(destination)) break;

            for (RoadEdge road : roadGraph.get(cur.location)) {
                if (!road.active) continue; // Skip closed roads
                int nd = cur.distance + road.time;
                if (nd < distance.get(road.to)) {
                    distance.put(road.to, nd);
                    previous.put(road.to, cur.location);
                    pq.add(new RouteState(road.to, nd));
                }
            }
        }

        if (distance.get(destination) == Integer.MAX_VALUE) return null;

        LinkedList<String> path = new LinkedList<>();
        String cur = destination;
        while (cur != null) {
            path.addFirst(cur);
            if (cur.equals(start)) break;
            cur = previous.get(cur);
        }

        if (path.isEmpty() || !path.getFirst().equals(start)) return null;
        return new RouteResult(path, distance.get(destination));
    }

    private ResponseUnit findNearestUnit(Incident i, RouteResult[] holder) {
        ResponseUnit selected = null;
        int shortest = Integer.MAX_VALUE;
        RouteResult best = null;

        for (ResponseUnit u : units.values()) {
            if (!u.available || !u.canHandle(i.type)) continue;
            RouteResult rr = findRoute(u.location, i.location);
            if (rr != null && rr.time < shortest) {
                shortest = rr.time;
                selected = u;
                best = rr;
            }
        }
        holder[0] = best;
        return selected;
    }

    private Map<String, Object> result(boolean ok, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", ok);
        m.put("message", message);
        return m;
    }

    // =========================================================================
    // 8. PUBLIC OPERATIONS
    // =========================================================================

    public synchronized Map<String, Object> report(Map<String, Object> body) throws SQLException {
        String location = String.valueOf(body.getOrDefault("location", "")).trim();
        String type = String.valueOf(body.getOrDefault("type", "")).trim();
        String description = String.valueOf(body.getOrDefault("description", "")).trim();

        if (!roadGraph.containsKey(location)) return result(false, "Unknown location: " + location);
        if (!Set.of("Fire", "Industrial Fire", "Rescue", "Accident").contains(type)) {
            return result(false, "Invalid incident type: " + type);
        }

        int intensity = number(body, "intensity", 1, 10);
        int population = number(body, "population", 0, 100000);
        boolean critical = Boolean.parseBoolean(String.valueOf(body.getOrDefault("critical", "false")));

        if (intensity < 1 || population < 0) {
            return result(false, "Invalid severity (1-10) or population count (>= 0).");
        }

        counter++;
        String id = "I" + String.format("%02d", counter);
        String token = generateToken();
        Incident i = new Incident(id, location, type, intensity, population, critical, counter,
                "Pending", "None", description, token, new Timestamp(System.currentTimeMillis()));

        incidents.put(i.id, i);
        queue.add(i);
        persistAll();

        try (Connection c = Database.getConnection()) {
            logStatus(c, i.id, "Pending", "Incident reported by public user. Waiting for dispatch.", "PUBLIC");
        }

        Map<String, Object> out = result(true, "Incident reported successfully.");
        out.put("incidentId", i.id);
        out.put("trackingToken", token);
        out.put("incident", i.publicJson());
        return out;
    }

    public synchronized Map<String, Object> trackIncident(String id, String token) throws SQLException {
        if (id == null || id.isBlank() || token == null || token.isBlank()) {
            return result(false, "Incident ID and Tracking Token are required.");
        }
        Incident i = incidents.get(id.trim());
        if (i == null || !token.trim().equals(i.trackingToken)) {
            return result(false, "Invalid incident reference or tracking token.");
        }

        Map<String, Object> out = result(true, "Incident found.");
        out.put("incident", i.publicJson());

        if (!"None".equals(i.assignedUnit) && units.containsKey(i.assignedUnit)) {
            ResponseUnit u = units.get(i.assignedUnit);
            Map<String, Object> unitInfo = new LinkedHashMap<>();
            unitInfo.put("id", u.id);
            unitInfo.put("type", u.type);
            unitInfo.put("location", u.location);
            unitInfo.put("status", u.status);
            unitInfo.put("route", u.route);
            unitInfo.put("routeIndex", u.routeIndex);
            out.put("assignedUnit", unitInfo);
        } else {
            out.put("assignedUnit", null);
        }

        List<Map<String, Object>> history = getIncidentHistory(i.id);
        out.put("history", history);
        return out;
    }

    public synchronized Map<String, Object> addIncidentUpdate(Map<String, Object> body) throws SQLException {
        String id = String.valueOf(body.getOrDefault("id", "")).trim();
        String token = String.valueOf(body.getOrDefault("token", "")).trim();
        String note = String.valueOf(body.getOrDefault("note", "")).trim();

        if (id.isBlank() || token.isBlank() || note.isBlank()) {
            return result(false, "ID, token, and update note are required.");
        }

        Incident i = incidents.get(id);
        if (i == null || !token.equals(i.trackingToken)) {
            return result(false, "Invalid incident reference or tracking token.");
        }

        if ("Completed".equalsIgnoreCase(i.status)) {
            return result(false, "This incident has already been completed. No further updates are accepted.");
        }

        try (Connection c = Database.getConnection()) {
            logStatus(c, i.id, i.status, note, "PUBLIC");
        }

        Map<String, Object> out = result(true, "Incident update added successfully.");
        out.put("history", getIncidentHistory(i.id));
        return out;
    }

    public List<Map<String, Object>> getIncidentHistory(String incidentId) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT * FROM status_history WHERE incident_id=? ORDER BY created_at ASC, id ASC";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, incidentId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getLong("id"));
                    m.put("incidentId", r.getString("incident_id"));
                    m.put("status", r.getString("status"));
                    m.put("note", r.getString("note"));
                    m.put("source", r.getString("source"));
                    m.put("createdAt", r.getTimestamp("created_at").toString());
                    list.add(m);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching history: " + e.getMessage());
        }
        return list;
    }

    public synchronized Map<String, Object> route(String start, String end) {
        if (start == null || end == null || start.isBlank() || end.isBlank()) {
            return result(false, "Start and destination locations are required.");
        }
        RouteResult rr = findRoute(start.trim(), end.trim());
        if (rr == null) {
            return result(false, "No reachable route exists between " + start + " and " + end + ". Check road closures.");
        }
        Map<String, Object> out = result(true, "Fastest route found.");
        out.put("route", rr.json());
        return out;
    }

    public Set<String> getLocations() {
        return new TreeSet<>(roadGraph.keySet());
    }

    // =========================================================================
    // 9. ADMIN OPERATIONS & DISPATCH
    // =========================================================================

    public synchronized Map<String, Object> adminDashboard() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalIncidents", incidents.size());
        m.put("pendingCount", queue.size());
        long active = units.values().stream().filter(u -> !u.available).count();
        m.put("activeCount", active);
        long completed = incidents.values().stream().filter(i -> "Completed".equalsIgnoreCase(i.status)).count();
        m.put("completedCount", completed);
        long availableUnits = units.values().stream().filter(u -> u.available).count();
        m.put("availableUnits", availableUnits);
        m.put("totalUnits", units.size());

        int closedRoads = 0;
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, List<RoadEdge>> entry : roadGraph.entrySet()) {
            for (RoadEdge e : entry.getValue()) {
                String key = entry.getKey().compareTo(e.to) < 0 ? (entry.getKey() + "||" + e.to) : (e.to + "||" + entry.getKey());
                if (seen.add(key) && !e.active) {
                    closedRoads++;
                }
            }
        }
        m.put("closedRoads", closedRoads);
        m.put("incidents", sortedIncidents());
        m.put("units", unitList());
        m.put("locations", getLocations());
        return m;
    }

    public synchronized List<Map<String, Object>> sortedIncidents() {
        List<Incident> list = new ArrayList<>(incidents.values());
        list.sort(PRIORITY);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Incident i : list) out.add(i.json());
        return out;
    }

    public synchronized List<Map<String, Object>> unitList() {
        List<ResponseUnit> list = new ArrayList<>(units.values());
        list.sort(Comparator.comparing(u -> u.id));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResponseUnit u : list) out.add(u.json());
        return out;
    }

    public synchronized List<Map<String, Object>> adminIncidents(String search, String status, String type) {
        List<Incident> list = new ArrayList<>(incidents.values());
        list.sort(PRIORITY);

        String s = search == null ? "" : search.trim().toLowerCase();
        String st = status == null ? "" : status.trim();
        String ty = type == null ? "" : type.trim();

        List<Map<String, Object>> out = new ArrayList<>();
        for (Incident i : list) {
            if (!st.isBlank() && !st.equalsIgnoreCase("ALL") && !i.status.equalsIgnoreCase(st)) {
                continue;
            }
            if (!ty.isBlank() && !ty.equalsIgnoreCase("ALL") && !i.type.equalsIgnoreCase(ty)) {
                continue;
            }
            if (!s.isBlank()) {
                boolean match = i.id.toLowerCase().contains(s) ||
                                i.location.toLowerCase().contains(s) ||
                                i.type.toLowerCase().contains(s) ||
                                i.description.toLowerCase().contains(s) ||
                                i.assignedUnit.toLowerCase().contains(s);
                if (!match) continue;
            }
            out.add(i.json());
        }
        return out;
    }

    public synchronized List<Map<String, Object>> adminListRoads() {
        List<Map<String, Object>> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, List<RoadEdge>> entry : roadGraph.entrySet()) {
            for (RoadEdge edge : entry.getValue()) {
                String key = entry.getKey().compareTo(edge.to) < 0 ? (entry.getKey() + "||" + edge.to) : (edge.to + "||" + entry.getKey());
                if (seen.add(key)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", entry.getKey().compareTo(edge.to) < 0 ? entry.getKey() : edge.to);
                    m.put("to", entry.getKey().compareTo(edge.to) < 0 ? edge.to : entry.getKey());
                    m.put("travelTime", edge.time);
                    m.put("active", edge.active);
                    list.add(m);
                }
            }
        }
        list.sort(Comparator.comparing(m -> String.valueOf(m.get("from")) + " " + String.valueOf(m.get("to"))));
        return list;
    }

    public synchronized Map<String, Object> dispatch() throws SQLException {
        if (queue.isEmpty()) return result(false, "No pending incidents in the priority queue.");

        Incident i = queue.poll();
        RouteResult[] holder = new RouteResult[1];
        ResponseUnit u = findNearestUnit(i, holder);

        if (u == null) {
            queue.add(i);
            return result(false, "No suitable unit available or incident " + i.id + " is currently unreachable. Incident remains pending.");
        }

        RouteResult rr = holder[0];
        u.assign(i.id, rr.path);
        i.assignedUnit = u.id;
        i.status = "En Route";

        try (Connection c = Database.getConnection()) {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO dispatch_history(incident_id,unit_id,route_text,travel_time) VALUES(?,?,?,?)")) {
                p.setString(1, i.id);
                p.setString(2, u.id);
                p.setString(3, String.join(" -> ", rr.path));
                p.setInt(4, rr.time);
                p.executeUpdate();
            }
            logStatus(c, i.id, "En Route",
                    "Dispatched " + u.type + " " + u.id + " from " + u.station + ". Route: " + String.join(" -> ", rr.path) + " (est. travel time: " + rr.time + " min).", "SYSTEM");
        }

        persistAll();
        Map<String, Object> out = result(true, "Unit " + u.id + " assigned to incident " + i.id + ".");
        out.put("incident", i.json());
        out.put("unit", u.json());
        out.put("route", rr.json());
        out.put("notification", u.type + " " + u.id + " dispatched. Estimated travel time: " + rr.time + " minutes.");
        return out;
    }

    public synchronized Map<String, Object> manualDispatch(String incidentId, String unitId) throws SQLException {
        if (incidentId == null || incidentId.isBlank()) {
            return result(false, "Incident ID is required for manual dispatch.");
        }
        Incident i = incidents.get(incidentId.trim());
        if (i == null) return result(false, "Incident " + incidentId + " not found.");
        if (!"Pending".equalsIgnoreCase(i.status)) {
            return result(false, "Incident " + incidentId + " is not pending (current status: " + i.status + ").");
        }

        ResponseUnit u;
        RouteResult rr;

        if (unitId != null && !unitId.isBlank()) {
            u = units.get(unitId.trim());
            if (u == null) return result(false, "Unit " + unitId + " not found.");
            if (!u.available) return result(false, "Unit " + unitId + " is currently unavailable.");
            if (!u.canHandle(i.type)) {
                return result(false, "Unit " + unitId + " (" + u.type + ") lacks required capability for " + i.type + ".");
            }
            rr = findRoute(u.location, i.location);
            if (rr == null) return result(false, "No reachable route from unit " + u.id + " at " + u.location + " to " + i.location + ".");
        } else {
            RouteResult[] holder = new RouteResult[1];
            u = findNearestUnit(i, holder);
            if (u == null) return result(false, "No compatible available unit found for incident " + i.id + ".");
            rr = holder[0];
        }

        queue.remove(i);
        u.assign(i.id, rr.path);
        i.assignedUnit = u.id;
        i.status = "En Route";

        try (Connection c = Database.getConnection()) {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO dispatch_history(incident_id,unit_id,route_text,travel_time) VALUES(?,?,?,?)")) {
                p.setString(1, i.id);
                p.setString(2, u.id);
                p.setString(3, String.join(" -> ", rr.path));
                p.setInt(4, rr.time);
                p.executeUpdate();
            }
            logStatus(c, i.id, "En Route",
                    "Admin manually dispatched " + u.type + " " + u.id + ". Route: " + String.join(" -> ", rr.path) + " (est. travel time: " + rr.time + " min).", "ADMIN");
        }

        persistAll();
        Map<String, Object> out = result(true, "Unit " + u.id + " manually dispatched to " + i.id + ".");
        out.put("incident", i.json());
        out.put("unit", u.json());
        out.put("route", rr.json());
        return out;
    }

    public synchronized Map<String, Object> updateIncident(Map<String, Object> body) throws SQLException {
        String id = String.valueOf(body.getOrDefault("id", "")).trim();
        Incident i = incidents.get(id);
        if (i == null) return result(false, "Incident " + id + " not found.");
        if (!"Pending".equals(i.status)) {
            return result(false, "Only pending incidents can have their priority modified.");
        }

        int intensity = number(body, "intensity", 1, 10);
        int population = number(body, "population", 0, 100000);
        if (intensity < 1 || population < 0) {
            return result(false, "Invalid severity (1-10) or population count.");
        }

        queue.remove(i);
        i.intensity = intensity;
        i.population = population;
        i.critical = Boolean.parseBoolean(String.valueOf(body.getOrDefault("critical", "false")));
        queue.add(i);

        try (Connection c = Database.getConnection()) {
            logStatus(c, i.id, i.status,
                    "Priority revised: Severity " + i.intensity + "/10, People " + i.population + ", Critical: " + (i.critical ? "Yes" : "No"), "ADMIN");
        }

        persistAll();
        return result(true, "Incident " + i.id + " updated. Priority queue reorganized.");
    }

    public synchronized Map<String, Object> track(String id) throws SQLException {
        if (id == null || id.isBlank()) return result(false, "Unit ID is required.");
        ResponseUnit u = units.get(id.trim());
        if (u == null) return result(false, "Unit " + id + " not found.");
        if (u.available) return result(false, "Unit " + id + " is not currently dispatched.");

        Incident i = incidents.get(u.incidentId);
        if (i == null) return result(false, "Assigned incident " + u.incidentId + " not found.");

        RouteResult rr = findRoute(u.location, i.location);
        if (rr == null) return result(false, "No reachable route from " + u.location + " to " + i.location + ". Check road closures.");

        u.route = new ArrayList<>(rr.path);
        u.routeIndex = 0;

        if (u.location.equals(i.location)) {
            u.status = "Reached";
            i.status = "Reached";
            try (Connection c = Database.getConnection()) {
                logStatus(c, i.id, "Reached", "Unit " + u.id + " has arrived at the incident site (" + i.location + ").", "SYSTEM");
            }
            persistAll();
            Map<String, Object> out = result(true, "Unit " + id + " has reached the incident location.");
            out.put("unit", u.json());
            out.put("incident", i.json());
            return out;
        }

        if (u.route.size() < 2) {
            return result(false, "No forward segment available along route.");
        }

        u.location = u.route.get(1);
        u.routeIndex = 1;

        if (u.location.equals(i.location)) {
            u.status = "Reached";
            i.status = "Reached";
            try (Connection c = Database.getConnection()) {
                logStatus(c, i.id, "Reached", "Unit " + u.id + " has arrived at the incident site (" + i.location + ").", "SYSTEM");
            }
        } else {
            u.status = "En Route";
            i.status = "En Route";
            try (Connection c = Database.getConnection()) {
                logStatus(c, i.id, "En Route", "Unit " + u.id + " advanced to " + u.location + ".", "SYSTEM");
            }
        }

        persistAll();
        Map<String, Object> out = result(true, "Unit " + id + " advanced to " + u.location + ".");
        out.put("unit", u.json());
        out.put("incident", i.json());
        out.put("route", rr.json());
        return out;
    }

    public synchronized Map<String, Object> complete(String id) throws SQLException {
        if (id == null || id.isBlank()) return result(false, "Unit ID is required.");
        ResponseUnit u = units.get(id.trim());
        if (u == null) return result(false, "Unit " + id + " not found.");
        if (u.available) return result(false, "Unit " + id + " is already available.");

        Incident i = incidents.get(u.incidentId);
        if (i == null) return result(false, "Assigned incident not found.");

        i.status = "Completed";
        u.complete();

        try (Connection c = Database.getConnection()) {
            logStatus(c, i.id, "Completed", "Mission completed. Unit " + u.id + " returned to availability.", "ADMIN");
        }

        persistAll();
        Map<String, Object> out = result(true, "Incident " + i.id + " marked Completed. Unit " + u.id + " is now available.");
        out.put("notification", "Mission completed.");

        if (!queue.isEmpty()) {
            Map<String, Object> next = dispatch();
            out.put("nextDispatch", next);
        }
        return out;
    }

    public synchronized Map<String, Object> closeRoad(String a, String b) throws SQLException {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return result(false, "Both locations are required.");
        }
        a = a.trim();
        b = b.trim();
        if (!roadGraph.containsKey(a) || !roadGraph.containsKey(b)) {
            return result(false, "Unknown road location.");
        }

        boolean found = false;
        for (RoadEdge e : roadGraph.get(a)) {
            if (e.to.equals(b)) {
                e.active = false;
                found = true;
            }
        }
        for (RoadEdge e : roadGraph.get(b)) {
            if (e.to.equals(a)) {
                e.active = false;
                found = true;
            }
        }

        if (!found) return result(false, "No road connection exists between " + a + " and " + b + ".");

        List<String> updates = new ArrayList<>();
        for (ResponseUnit u : units.values()) {
            if (!u.available) {
                Incident i = incidents.get(u.incidentId);
                if (i != null) {
                    RouteResult rr = findRoute(u.location, i.location);
                    if (rr == null) {
                        u.route.clear();
                        u.routeIndex = 0;
                        updates.add("Warning: No alternate route currently available for " + u.id + " to reach " + i.location);
                        try (Connection c = Database.getConnection()) {
                            logStatus(c, i.id, u.status, "Road closed between " + a + " and " + b + ". Unit " + u.id + " is currently blocked without alternate route.", "SYSTEM");
                        }
                    } else {
                        u.route = new ArrayList<>(rr.path);
                        u.routeIndex = 0;
                        updates.add("Unit " + u.id + " rerouted: " + String.join(" -> ", rr.path) + " (est. travel time: " + rr.time + " min)");
                        try (Connection c = Database.getConnection()) {
                            logStatus(c, i.id, u.status, "Road closed between " + a + " and " + b + ". Unit " + u.id + " rerouted: " + String.join(" -> ", rr.path), "SYSTEM");
                        }
                    }
                }
            }
        }

        persistAll();
        Map<String, Object> out = result(true, "Road closed between " + a + " and " + b + ".");
        out.put("updates", updates);
        return out;
    }

    public synchronized Map<String, Object> reopenRoad(String a, String b) throws SQLException {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return result(false, "Both locations are required.");
        }
        a = a.trim();
        b = b.trim();
        if (!roadGraph.containsKey(a) || !roadGraph.containsKey(b)) {
            return result(false, "Unknown road location.");
        }

        boolean found = false;
        for (RoadEdge e : roadGraph.get(a)) {
            if (e.to.equals(b)) {
                e.active = true;
                found = true;
            }
        }
        for (RoadEdge e : roadGraph.get(b)) {
            if (e.to.equals(a)) {
                e.active = true;
                found = true;
            }
        }

        if (!found) return result(false, "No road connection exists between " + a + " and " + b + ".");

        List<String> updates = new ArrayList<>();
        for (ResponseUnit u : units.values()) {
            if (!u.available) {
                Incident i = incidents.get(u.incidentId);
                if (i != null) {
                    RouteResult rr = findRoute(u.location, i.location);
                    if (rr != null) {
                        u.route = new ArrayList<>(rr.path);
                        u.routeIndex = 0;
                        updates.add("Unit " + u.id + " updated route: " + String.join(" -> ", rr.path) + " (travel time: " + rr.time + " min)");
                    }
                }
            }
        }

        persistAll();
        Map<String, Object> out = result(true, "Road reopened between " + a + " and " + b + ".");
        out.put("updates", updates);
        return out;
    }

    // =========================================================================
    // 10. ADMIN AUTHENTICATION (PBKDF2 Salted Hashes)
    // =========================================================================

    public static String hashPassword(String password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 65536, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = f.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2 algorithm unavailable", e);
        }
    }

    public static byte[] generateSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public void initAdminIfMissing() {
        String countSql = "SELECT COUNT(*) FROM admin_users";
        try (Connection c = Database.getConnection();
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(countSql)) {
            r.next();
            if (r.getInt(1) == 0) {
                byte[] salt = generateSalt();
                String saltStr = Base64.getEncoder().encodeToString(salt);
                String hash = hashPassword("Admin@123", salt);
                String insertSql = "INSERT INTO admin_users(username, password_hash, salt) VALUES(?,?,?)";
                try (PreparedStatement p = c.prepareStatement(insertSql)) {
                    p.setString(1, "admin");
                    p.setString(2, hash);
                    p.setString(3, saltStr);
                    p.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("Notice: admin_users table init check: " + e.getMessage());
        }
    }

    public boolean verifyAdmin(String username, String password) {
        if (username == null || password == null) return false;
        String sql = "SELECT password_hash, salt FROM admin_users WHERE username=?";
        try (Connection c = Database.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, username.trim());
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) {
                    String storedHash = r.getString("password_hash");
                    String saltStr = r.getString("salt");
                    byte[] salt = Base64.getDecoder().decode(saltStr);
                    String computedHash = hashPassword(password, salt);
                    return constantTimeEquals(storedHash, computedHash);
                }
            }
        } catch (Exception e) {
            System.err.println("Admin verification error: " + e.getMessage());
        }
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private int number(Map<String, Object> b, String key, int min, int max) {
        try {
            int n = Integer.parseInt(String.valueOf(b.get(key)));
            return (n >= min && n <= max) ? n : -1;
        } catch (Exception e) {
            return -1;
        }
    }
}