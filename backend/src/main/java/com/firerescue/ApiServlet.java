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
 * Distinguishes Public endpoints from Admin-authenticated endpoints.
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
                String end = req.getParameter("end");
                send(resp, 200, system.route(start, end));
                return;
            }
            if ("/public/track".equals(path)) {
                String id = req.getParameter("id");
                String token = req.getParameter("token");
                Map<String, Object> out = system.trackIncident(id, token);
                int code = (Boolean.TRUE.equals(out.get("success"))) ? 200 : 403;
                send(resp, code, out);
                return;
            }

            // --- Admin Auth Status Check ---
            if ("/admin/session".equals(path)) {
                HttpSession session = req.getSession(false);
                boolean auth = session != null && session.getAttribute("adminUser") != null;
                String user = auth ? String.valueOf(session.getAttribute("adminUser")) : null;
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
                        String type = req.getParameter("type");
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

            // --- Legacy Fallback for backward compatibility ---
            if ("/dashboard".equals(path)) {
                send(resp, 200, system.adminDashboard());
                return;
            }
            if ("/incidents".equals(path)) {
                send(resp, 200, system.sortedIncidents());
                return;
            }
            if ("/units".equals(path)) {
                send(resp, 200, system.unitList());
                return;
            }

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

            // --- Admin Login & Logout ---
            if ("/admin/login".equals(path)) {
                String username = String.valueOf(body.getOrDefault("username", "")).trim();
                String password = String.valueOf(body.getOrDefault("password", ""));

                if (system.verifyAdmin(username, password)) {
                    HttpSession session = req.getSession(true);
                    session.setAttribute("adminUser", username);
                    session.setMaxInactiveInterval(1800); // 30 minutes
                    send(resp, 200, Map.of("success", true, "message", "Login successful.", "username", username));
                } else {
                    send(resp, 401, Map.of("success", false, "message", "Invalid admin credentials."));
                }
                return;
            }

            if ("/admin/logout".equals(path)) {
                HttpSession session = req.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                send(resp, 200, Map.of("success", true, "message", "Logged out successfully."));
                return;
            }

            // --- Protected Admin Actions ---
            if (path.startsWith("/admin/") || "/dispatch".equals(path) || "/track".equals(path) ||
                "/update".equals(path) || "/complete".equals(path) || "/close-road".equals(path)) {

                // Legacy fallback paths can execute or require admin
                if (path.startsWith("/admin/") && !requireAdmin(req, resp)) return;

                Map<String, Object> out;
                switch (path) {
                    case "/admin/dispatch":
                    case "/dispatch":
                        out = system.dispatch();
                        break;
                    case "/admin/manual-dispatch":
                        String incId = String.valueOf(body.getOrDefault("incidentId", ""));
                        String unitId = String.valueOf(body.getOrDefault("unitId", ""));
                        out = system.manualDispatch(incId, unitId);
                        break;
                    case "/admin/update-incident":
                    case "/update":
                        out = system.updateIncident(body);
                        break;
                    case "/admin/track-unit":
                    case "/track":
                        out = system.track(String.valueOf(body.getOrDefault("unitId", "")));
                        break;
                    case "/admin/complete-mission":
                    case "/complete":
                        out = system.complete(String.valueOf(body.getOrDefault("unitId", "")));
                        break;
                    case "/admin/close-road":
                    case "/close-road":
                        out = system.closeRoad(String.valueOf(body.getOrDefault("from", "")),
                                               String.valueOf(body.getOrDefault("to", "")));
                        break;
                    case "/admin/reopen-road":
                        out = system.reopenRoad(String.valueOf(body.getOrDefault("from", "")),
                                                String.valueOf(body.getOrDefault("to", "")));
                        break;
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