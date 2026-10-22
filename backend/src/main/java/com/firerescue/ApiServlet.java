package com.firerescue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.SQLException;
import java.util.*;

@WebServlet(urlPatterns="/api/*")
public class ApiServlet extends HttpServlet {
    private static final Gson GSON=new Gson();
    private static final Type MAP_TYPE=new TypeToken<Map<String,Object>>(){}.getType();
    private RescueSystem system;

    @Override public void init() {
        try { system=new RescueSystem(); }
        catch(Exception e) { throw new IllegalStateException("Cannot initialize RescueSystem. Check MySQL credentials and schema.sql.",e); }
    }
    @Override protected void doGet(HttpServletRequest req,HttpServletResponse resp) throws IOException {
        try {
            String path=req.getPathInfo()==null?"/":req.getPathInfo();
            Object data;
            switch(path) {
                case "/dashboard": data=system.dashboard(); break;
                case "/incidents": data=system.sortedIncidents(); break;
                case "/units": data=system.unitList(); break;
                case "/route": data=system.route(req.getParameter("start"),req.getParameter("end")); break;
                default: send(resp,404,Map.of("success",false,"message","Unknown API endpoint."));return;
            }
            send(resp,200,data);
        } catch(Exception e) { send(resp,500,Map.of("success",false,"message",safeMessage(e))); }
    }
    @Override protected void doPost(HttpServletRequest req,HttpServletResponse resp) throws IOException {
        try {
            Map<String,Object> body=GSON.fromJson(req.getReader(),MAP_TYPE);
            if(body==null) body=new HashMap<>();
            String path=req.getPathInfo()==null?"/":req.getPathInfo();
            Map<String,Object> out;
            switch(path) {
                case "/incidents": out=system.report(body); break;
                case "/dispatch": out=system.dispatch(); break;
                case "/update": out=system.updateIncident(body); break;
                case "/track": out=system.track(String.valueOf(body.getOrDefault("unitId",""))); break;
                case "/close-road": out=system.closeRoad(String.valueOf(body.getOrDefault("from","")),String.valueOf(body.getOrDefault("to",""))); break;
                case "/complete": out=system.complete(String.valueOf(body.getOrDefault("unitId",""))); break;
                default: send(resp,404,Map.of("success",false,"message","Unknown API endpoint."));return;
            }
            send(resp,200,out);
        } catch(Exception e) { send(resp,500,Map.of("success",false,"message",safeMessage(e))); }
    }
    private String safeMessage(Exception e) {
        if(e instanceof SQLException) return "Database operation failed. Check MySQL is running and db.properties is correct.";
        return e.getMessage()==null?"Unexpected server error.":e.getMessage();
    }
    private void send(HttpServletResponse resp,int status,Object data) throws IOException {
        resp.setStatus(status);resp.setCharacterEncoding("UTF-8");resp.setContentType("application/json");
        resp.getWriter().write(GSON.toJson(data));
    }
}
