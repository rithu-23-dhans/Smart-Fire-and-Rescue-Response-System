package com.firerescue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.*;

/**
 * REST API Servlet for Smart Fire & Rescue Response System.
 * Distinguishes Public endpoints from Admin-authenticated endpoints,
 * and exposes Citizen profile endpoints with remember-me token support.
 */
@WebServlet(urlPatterns = "/api/*")
public class ApiServlet extends HttpServlet {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private RescueSystem system;

    @Override
    public void init() {
        try {
            system = new RescueSystem();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize RescueSystem. Check MySQL credentials and schema.sql.", e);
        }
    }

    // =========================================================================
    // GET REQUEST HANDLER
    // =========================================================================

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = getPath(req);

            // --- Public Endpoints ---
            if ("/public/locations".equals(path) || "/locations".equals(path)) {
                send(resp, 200, Map.of("success", true, "locations", system.getLocations()));
                return;
            }
            if ("/public/route".equals(path) || "/route".equals(path)) {
                String start = req.getParameter("start");
                String end   = req.getParameter("end");
                send(resp, 200, system.route(start, end));
                return;
            }
            if ("/public/track".equals(path)) {
                String id    = req.getParameter("id");
                String token = req.getParameter("token");
                Map<String, Object> out = system.trackIncident(id, token);
                int code = Boolean.TRUE.equals(out.get("success")) ? 200 : 403;
                send(resp, code, out);
                return;
            }

            // --- Citizen session check (uses remember-me token from request header/param) ---
            if ("/public/citizen/session".equals(path)) {
                Map<String, Object> session = getCitizenSession(req);
                if (session != null) {
                    // Attach refreshed token in header so client can update localStorage
                    String newToken = (String) session.get("rememberToken");
                    if (newToken != null) resp.setHeader("X-Remember-Token", newToken);
                    send(resp, 200, Map.of(
                        "success", true,
                        "loggedIn", true,
                        "citizenId", session.get("citizenId"),
                        "username",  session.get("username"),
                        "email",     session.get("email"),
                        "rememberToken", newToken != null ? newToken : ""
                    ));
                } else {
                    send(resp, 200, Map.of("success", true, "loggedIn", false));
                }
                return;
            }

            // --- Citizen: my incidents (session-gated via remember-me token) ---
            if ("/public/my-incidents".equals(path)) {
                Map<String, Object> session = getCitizenSession(req);
                if (session == null) {
                    send(resp, 401, Map.of("success", false, "message", "Please log in to view your incidents."));
                    return;
                }
                int citizenId = ((Number) session.get("citizenId")).intValue();
                String newToken = (String) session.get("rememberToken");
                if (newToken != null) resp.setHeader("X-Remember-Token", newToken);
                List<Map<String, Object>> incidents = system.getMyIncidents(citizenId);
                send(resp, 200, Map.of("success", true, "incidents", incidents));
                return;
            }

            // --- Admin Auth Status Check ---
            if ("/admin/session".equals(path)) {
                HttpSession adminSess = req.getSession(false);
                boolean auth = adminSess != null && adminSess.getAttribute("adminUser") != null;
                String user  = auth ? String.valueOf(adminSess.getAttribute("adminUser")) : null;
                send(resp, 200, Map.of("authenticated", auth, "username", user == null ? "" : user));
                return;
            }

            // --- Protected Admin Endpoints ---
            if (path.startsWith("/admin/")) {
                if (!requireAdmin(req, resp)) return;

                switch (path) {
                    case "/admin/dashboard":
                        send(resp, 200, system.adminDashboard());
                        return;
                    case "/admin/incidents":
                        String search = req.getParameter("search");
                        String status = req.getParameter("status");
                        String type   = req.getParameter("type");
                        send(resp, 200, Map.of("success", true, "incidents", system.adminIncidents(search, status, type)));
                        return;
                    case "/admin/units":
                        send(resp, 200, Map.of("success", true, "units", system.unitList()));
                        return;
                    case "/admin/roads":
                        send(resp, 200, Map.of("success", true, "roads", system.adminListRoads()));
                        return;
                    case "/admin/incident-history":
                        String incId = req.getParameter("id");
                        if (incId == null || incId.isBlank()) {
                            send(resp, 400, Map.of("success", false, "message", "Incident ID is required."));
                            return;
                        }
                        send(resp, 200, Map.of("success", true, "history", system.getIncidentHistory(incId.trim())));
                        return;
                    default:
                        send(resp, 404, Map.of("success", false, "message", "Unknown admin endpoint: " + path));
                        return;
                }
            }

            // --- Legacy Fallback ---
            if ("/dashboard".equals(path)) { send(resp, 200, system.adminDashboard()); return; }
            if ("/incidents".equals(path)) { send(resp, 200, system.sortedIncidents()); return; }
            if ("/units".equals(path))     { send(resp, 200, system.unitList()); return; }

            send(resp, 404, Map.of("success", false, "message", "Unknown API endpoint: " + path));
        } catch (Exception e) {
            send(resp, 500, Map.of("success", false, "message", safeMessage(e)));
        }
    }

    // =========================================================================
    // POST REQUEST HANDLER
    // =========================================================================

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            String path = getPath(req);
            Map<String, Object> body = GSON.fromJson(req.getReader(), MAP_TYPE);
            if (body == null) body = new HashMap<>();

            // --- Public Incident Submission ---
            if ("/public/incidents".equals(path) || "/incidents".equals(path)) {
                // Inject citizenId from session if the user is logged in
                Map<String, Object> citizenSess = getCitizenSession(req);
                if (citizenSess != null && !body.containsKey("citizenId")) {
                    body.put("citizenId", citizenSess.get("citizenId"));
                    String newToken = (String) citizenSess.get("rememberToken");
                    if (newToken != null) resp.setHeader("X-Remember-Token", newToken);
                }
                Map<String, Object> out = system.report(body);
                int code = Boolean.TRUE.equals(out.get("success")) ? 201 : 400;
                send(resp, code, out);
                return;
            }

            // --- Public Incident Update Note ---
            if ("/public/incident-update".equals(path)) {
                Map<String, Object> out = system.addIncidentUpdate(body);
                int code = Boolean.TRUE.equals(out.get("success")) ? 200 : 400;
                send(resp, code, out);
                return;
            }

            // ---- CITIZEN AUTHENTICATION ----

            // Register
            if ("/public/citizen/register".equals(path)) {
                String username      = str(body, "username");
                String email         = str(body, "password"); // will be overridden below
                email                = str(body, "email");
                String password      = str(body, "password");
                String incidentId    = str(body, "incidentId");
                String trackingToken = str(body, "trackingToken");

                Map<String, Object> out = system.registerCitizen(username, email, password,
                                                                  incidentId, trackingToken);
                if (Boolean.TRUE.equals(out.get("success"))) {
                    // Auto-login: create remember-me token via loginCitizen
                    Map<String, Object> login = system.loginCitizen(username, password);
                    if (Boolean.TRUE.equals(login.get("success"))) {
                        out.put("rememberToken", login.get("rememberToken"));
                        out.put("citizenId", login.get("citizenId"));
                    }
                }
                int code = Boolean.TRUE.equals(out.get("success")) ? 201 : 400;
                send(resp, code, out);
                return;
            }

            // Login
            if ("/public/citizen/login".equals(path)) {
                String username = str(body, "username");
                String password = str(body, "password");
                Map<String, Object> out = system.loginCitizen(username, password);
                int code = Boolean.TRUE.equals(out.get("success")) ? 200 : 401;
                send(resp, code, out);
                return;
            }

            // Logout — revoke the remember-me token
            if ("/public/citizen/logout".equals(path)) {
                String token = str(body, "rememberToken");
                system.revokeRememberToken(token);
                send(resp, 200, Map.of("success", true, "message", "Logged out."));
                return;
            }

            // Claim an anonymously-submitted incident
            if ("/public/citizen/claim-incident".equals(path)) {
                Map<String, Object> session = getCitizenSession(req);
                if (session == null) {
                    send(resp, 401, Map.of("success", false, "message", "Please log in first."));
                    return;
                }
                int citizenId    = ((Number) session.get("citizenId")).intValue();
                String incidentId    = str(body, "incidentId");
                String trackingToken = str(body, "trackingToken");
                String newToken = (String) session.get("rememberToken");
                if (newToken != null) resp.setHeader("X-Remember-Token", newToken);
                Map<String, Object> out = system.claimIncidentForCitizen(citizenId, incidentId, trackingToken);
                int code = Boolean.TRUE.equals(out.get("success")) ? 200 : 400;
                send(resp, code, out);
                return;
            }

            // Verify remember-me token (used on page-load to restore session)
            if ("/public/citizen/verify-token".equals(path)) {
                String token = str(body, "rememberToken");
                Map<String, Object> out = system.verifyRememberToken(token);
                int code = Boolean.TRUE.equals(out.get("success")) ? 200 : 401;
                send(resp, code, out);
                return;
            }

            // --- Admin Login & Logout ---
            if ("/admin/login".equals(path)) {
                String username = String.valueOf(body.getOrDefault("username", "")).trim();
                String password = String.valueOf(body.getOrDefault("password", ""));

                if (system.verifyAdmin(username, password)) {
                    HttpSession session = req.getSession(true);
                    session.setAttribute("adminUser", username);
                    session.setMaxInactiveInterval(1800);
                    send(resp, 200, Map.of("success", true, "message", "Login successful.", "username", username));
                } else {
                    send(resp, 401, Map.of("success", false, "message", "Invalid admin credentials."));
                }
                return;
            }

            if ("/admin/logout".equals(path)) {
                HttpSession session = req.getSession(false);
                if (session != null) session.invalidate();
                send(resp, 200, Map.of("success", true, "message", "Logged out successfully."));
                return;
            }

            // --- Protected Admin Actions ---
            if (path.startsWith("/admin/") || "/dispatch".equals(path) || "/track".equals(path) ||
                "/update".equals(path) || "/complete".equals(path) || "/close-road".equals(path)) {

                if (path.startsWith("/admin/") && !requireAdmin(req, resp)) return;

                Map<String, Object> out;
                switch (path) {
                    case "/admin/dispatch":
                    case "/dispatch":
                        out = system.dispatch(); break;
                    case "/admin/manual-dispatch":
                        String incId  = str(body, "incidentId");
                        String unitId = str(body, "unitId");
                        out = system.manualDispatch(incId, unitId); break;
                    case "/admin/update-incident":
                    case "/update":
                        out = system.updateIncident(body); break;
                    case "/admin/track-unit":
                    case "/track":
                        out = system.track(str(body, "unitId")); break;
                    case "/admin/complete-mission":
                    case "/complete":
                        out = system.complete(str(body, "unitId")); break;
                    case "/admin/close-road":
                    case "/close-road":
                        out = system.closeRoad(str(body, "from"), str(body, "to")); break;
                    case "/admin/reopen-road":
                        out = system.reopenRoad(str(body, "from"), str(body, "to")); break;
                    default:
                        send(resp, 404, Map.of("success", false, "message", "Unknown endpoint: " + path));
                        return;
                }
                send(resp, 200, out);
                return;
            }

            send(resp, 404, Map.of("success", false, "message", "Unknown API endpoint: " + path));
        } catch (Exception e) {
            send(resp, 500, Map.of("success", false, "message", safeMessage(e)));
        }
    }

    // =========================================================================
    // SECURITY & HELPER METHODS
    // =========================================================================

    /**
     * Resolves the citizen session from the X-Remember-Token request header.
     * Returns the verified session map (with rotated rememberToken) or null.
     */
    private Map<String, Object> getCitizenSession(HttpServletRequest req) {
        String token = req.getHeader("X-Remember-Token");
        if (token == null || token.isBlank()) return null;
        try {
            Map<String, Object> result = system.verifyRememberToken(token);
            return Boolean.TRUE.equals(result.get("success")) ? result : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean requireAdmin(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("adminUser") == null) {
            send(resp, 401, Map.of("success", false, "message", "Unauthorized. Administrator session required."));
            return false;
        }
        return true;
    }

    private String getPath(HttpServletRequest req) {
        String path = req.getPathInfo();
        return (path == null || path.isBlank()) ? "/" : path.trim();
    }

    private String str(Map<String, Object> b, String key) {
        return String.valueOf(b.getOrDefault(key, "")).trim();
    }

    private String safeMessage(Exception e) {
        if (e instanceof SQLException) {
            return "Database operation error: " + e.getMessage() + ". Check MySQL connection and credentials.";
        }
        return e.getMessage() == null ? "Unexpected server error." : e.getMessage();
    }

    private void send(HttpServletResponse resp, int status, Object data) throws IOException {
        resp.setStatus(status);
        resp.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.getWriter().write(GSON.toJson(data));
    }
}