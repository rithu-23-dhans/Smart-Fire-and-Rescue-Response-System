package com.firerescue;

import java.sql.*;
import java.util.*;

/**
 * Web-adapted core of the supplied Main.java. Priority and route selection rules
 * are retained: severity, critical flag, affected population, arrival order;
 * Dijkstra shortest travel time; nearest available compatible unit.
 */
public final class RescueSystem {
    public static final class Incident {
        public String id, location, type, status, assignedUnit;
        public int intensity, population, arrivalOrder;
        public boolean critical;
        Incident(String id, String location, String type, int intensity, int population,
                 boolean critical, int arrivalOrder, String status, String assignedUnit) {
            this.id=id; this.location=location; this.type=type; this.intensity=intensity;
            this.population=population; this.critical=critical; this.arrivalOrder=arrivalOrder;
            this.status=status; this.assignedUnit=assignedUnit;
        }
        Map<String,Object> json() {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",id); m.put("location",location); m.put("type",type);
            m.put("intensity",intensity); m.put("population",population); m.put("critical",critical);
            m.put("arrivalOrder",arrivalOrder); m.put("status",status); m.put("assignedUnit",assignedUnit);
            return m;
        }
    }
    public static final class Unit {
        public String id, type, station, location, status, incidentId;
        public boolean available;
        public Set<String> capabilities = new HashSet<>();
        public List<String> route = new ArrayList<>();
        public int routeIndex;
        Unit(String id,String type,String station,String location,String... capabilities) {
            this.id=id; this.type=type; this.station=station; this.location=location;
            this.available=true; this.status="Available"; this.incidentId="None";
            this.capabilities.addAll(Arrays.asList(capabilities));
        }
        Map<String,Object> json() {
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",id); m.put("type",type); m.put("station",station); m.put("location",location);
            m.put("available",available); m.put("status",status); m.put("incidentId",incidentId);
            m.put("capabilities",capabilities); m.put("route",route); m.put("routeIndex",routeIndex);
            return m;
        }
        void assign(String incidentId,List<String> route) {
            available=false; status="En Route"; this.incidentId=incidentId;
            this.route=new ArrayList<>(route); routeIndex=0;
        }
        void complete() {
            available=true; status="Available"; incidentId="None"; route.clear(); routeIndex=0;
        }
    }
    private static final class Road {
        String to; int time;
        Road(String to,int time){this.to=to;this.time=time;}
    }
    private static final class RouteState {
        String location; int distance;
        RouteState(String location,int distance){this.location=location;this.distance=distance;}
    }
    private static final class RouteResult {
        List<String> path; int time;
        RouteResult(List<String> path,int time){this.path=path;this.time=time;}
        Map<String,Object> json() {
            Map<String,Object> m=new LinkedHashMap<>(); m.put("path",path); m.put("time",time); return m;
        }
    }
    private static final Comparator<Incident> PRIORITY=(a,b)->{
        if(a.intensity!=b.intensity) return Integer.compare(b.intensity,a.intensity);
        if(a.critical!=b.critical) return a.critical?-1:1;
        if(a.population!=b.population) return Integer.compare(b.population,a.population);
        return Integer.compare(a.arrivalOrder,b.arrivalOrder);
    };

    private final Map<String,Unit> units=new HashMap<>();
    private final Map<String,Incident> incidents=new HashMap<>();
    private final Map<String,List<Road>> roads=new HashMap<>();
    private final PriorityQueue<Incident> queue=new PriorityQueue<>(PRIORITY);
    private int counter=0;

    public RescueSystem() throws SQLException { loadOrSeed(); }

    private void addLocation(String l){roads.putIfAbsent(l,new ArrayList<>());}
    private void addRoadMemory(String a,String b,int t){
        addLocation(a); addLocation(b); roads.get(a).add(new Road(b,t)); roads.get(b).add(new Road(a,t));
    }
    private void seedRoads() {
        addRoadMemory("Station1","TownCenter",4); addRoadMemory("Station1","ResidentialArea",6);
        addRoadMemory("Station1","IndustrialArea",8); addRoadMemory("Station2","TownCenter",5);
        addRoadMemory("Station2","Hospital",4); addRoadMemory("Station2","School",7);
        addRoadMemory("TownCenter","Market",3); addRoadMemory("TownCenter","Hospital",5);
        addRoadMemory("TownCenter","School",6); addRoadMemory("Market","ResidentialArea",4);
        addRoadMemory("Market","School",3); addRoadMemory("ResidentialArea","RiverBridge",5);
        addRoadMemory("RiverBridge","IndustrialArea",4); addRoadMemory("IndustrialArea","Warehouse",3);
        addRoadMemory("Hospital","School",4); addRoadMemory("Warehouse","AirportRoad",6);
        addRoadMemory("AirportRoad","RiverBridge",5); addRoadMemory("School","OldTown",4);
        addRoadMemory("OldTown","Market",5); addRoadMemory("Hospital","OldTown",6);
    }
    private void seedUnits() {
        units.put("FE01",new Unit("FE01","Fire Engine","Station1","Station1","Fire","Industrial Fire"));
        units.put("FE02",new Unit("FE02","Fire Engine","Station1","Station1","Fire"));
        units.put("RV01",new Unit("RV01","Rescue Van","Station2","Station2","Rescue","Accident"));
        units.put("RV02",new Unit("RV02","Rescue Van","Station2","Station2","Rescue","Accident"));
    }
    private void addSample(String location,String type,int intensity,int population,boolean critical) {
        counter++;
        Incident i=new Incident("I"+String.format("%02d",counter),location,type,intensity,population,
            critical,counter,"Pending","None");
        incidents.put(i.id,i); queue.add(i);
    }
    private void seedIncidents() {
        addSample("ResidentialArea","Fire",8,7,false);
        addSample("Hospital","Rescue",10,10,true);
        addSample("IndustrialArea","Industrial Fire",9,8,true);
        addSample("School","Rescue",7,9,true);
        addSample("Market","Fire",6,6,false);
        addSample("Warehouse","Industrial Fire",8,5,true);
        addSample("OldTown","Accident",6,4,false);
        addSample("RiverBridge","Rescue",9,7,true);
        addSample("AirportRoad","Accident",7,5,false);
        addSample("TownCenter","Fire",5,3,false);
        addSample("ResidentialArea","Rescue",7,6,false);
        addSample("School","Fire",8,8,true);
        addSample("Market","Accident",5,2,false);
        addSample("Hospital","Fire",9,9,true);
        addSample("IndustrialArea","Fire",7,6,true);
    }

    private void loadOrSeed() throws SQLException {
        try(Connection c=Database.getConnection()) {
            boolean hasIncidents;
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT COUNT(*) FROM incidents")) {
                r.next(); hasIncidents=r.getInt(1)>0;
            }
            if(!hasIncidents) {
                seedRoads(); seedUnits(); seedIncidents(); persistAll();
                return;
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM roads WHERE active=TRUE")) {
                while(r.next()) addRoadMemory(r.getString("from_location"),r.getString("to_location"),r.getInt("travel_time"));
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM response_units")) {
                while(r.next()) {
                    Unit u=new Unit(r.getString("id"),r.getString("unit_type"),r.getString("station"),
                        r.getString("location"));
                    String caps=r.getString("capabilities");
                    if(caps!=null&&!caps.isBlank()) u.capabilities.addAll(Arrays.asList(caps.split(",")));
                    u.available=r.getBoolean("available"); u.status=r.getString("status");
                    u.incidentId=r.getString("incident_id"); u.route=splitRoute(r.getString("route_text"));
                    u.routeIndex=r.getInt("route_index"); units.put(u.id,u);
                }
            }
            try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM incidents")) {
                while(r.next()) {
                    Incident i=new Incident(r.getString("id"),r.getString("location"),r.getString("type"),
                        r.getInt("intensity"),r.getInt("population_affected"),r.getBoolean("critical"),
                        r.getInt("arrival_order"),r.getString("status"),r.getString("assigned_unit"));
                    incidents.put(i.id,i); counter=Math.max(counter,i.arrivalOrder);
                    if("Pending".equals(i.status)) queue.add(i);
                }
            }
        }
    }
    private List<String> splitRoute(String route) {
        if(route==null||route.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(route.split(" -> ",-1)));
    }
    private void persistAll() throws SQLException {
        try(Connection c=Database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(Statement s=c.createStatement()) {
                    s.executeUpdate("DELETE FROM incidents");
                    s.executeUpdate("DELETE FROM response_units");
                    s.executeUpdate("DELETE FROM roads");
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO incidents(id,location,type,intensity,population_affected,critical,arrival_order,status,assigned_unit) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    for(Incident i:incidents.values()) {
                        p.setString(1,i.id);p.setString(2,i.location);p.setString(3,i.type);p.setInt(4,i.intensity);
                        p.setInt(5,i.population);p.setBoolean(6,i.critical);p.setInt(7,i.arrivalOrder);
                        p.setString(8,i.status);p.setString(9,i.assignedUnit);p.addBatch();
                    } p.executeBatch();
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO response_units(id,unit_type,station,location,available,status,incident_id,capabilities,route_text,route_index) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    for(Unit u:units.values()) {
                        p.setString(1,u.id);p.setString(2,u.type);p.setString(3,u.station);p.setString(4,u.location);
                        p.setBoolean(5,u.available);p.setString(6,u.status);p.setString(7,u.incidentId);
                        p.setString(8,String.join(",",u.capabilities));p.setString(9,String.join(" -> ",u.route));
                        p.setInt(10,u.routeIndex);p.addBatch();
                    } p.executeBatch();
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO roads(from_location,to_location,travel_time,active) VALUES(?,?,?,TRUE)")) {
                    for(Map.Entry<String,List<Road>> e:roads.entrySet()) for(Road r:e.getValue()) {
                        // Store each undirected edge once.
                        if(e.getKey().compareTo(r.to)<0) {
                            p.setString(1,e.getKey());p.setString(2,r.to);p.setInt(3,r.time);p.addBatch();
                        }
                    } p.executeBatch();
                }
                c.commit();
            } catch(SQLException ex) { c.rollback(); throw ex; }
            finally { c.setAutoCommit(true); }
        }
    }
    private RouteResult findRoute(String start,String destination) {
        if(!roads.containsKey(start)||!roads.containsKey(destination)) return null;
        Map<String,Integer> distance=new HashMap<>(); Map<String,String> previous=new HashMap<>();
        for(String l:roads.keySet()) distance.put(l,Integer.MAX_VALUE);
        distance.put(start,0);
        PriorityQueue<RouteState> pq=new PriorityQueue<>(Comparator.comparingInt(s->s.distance));
        pq.add(new RouteState(start,0));
        while(!pq.isEmpty()) {
            RouteState cur=pq.poll();
            if(cur.distance!=distance.get(cur.location)) continue;
            if(cur.location.equals(destination)) break;
            for(Road road:roads.get(cur.location)) {
                int nd=cur.distance+road.time;
                if(nd<distance.get(road.to)) {
                    distance.put(road.to,nd);previous.put(road.to,cur.location);pq.add(new RouteState(road.to,nd));
                }
            }
        }
        if(distance.get(destination)==Integer.MAX_VALUE) return null;
        LinkedList<String> path=new LinkedList<>(); String cur=destination;
        while(cur!=null) { path.addFirst(cur); if(cur.equals(start)) break; cur=previous.get(cur); }
        if(path.isEmpty()||!path.getFirst().equals(start)) return null;
        return new RouteResult(path,distance.get(destination));
    }
    private Unit findNearestUnit(Incident i, RouteResult[] holder) {
        Unit selected=null; int shortest=Integer.MAX_VALUE; RouteResult best=null;
        for(Unit u:units.values()) {
            if(!u.available||!u.capabilities.contains(i.type)) continue;
            RouteResult rr=findRoute(u.location,i.location);
            if(rr!=null&&rr.time<shortest) { shortest=rr.time;selected=u;best=rr; }
        }
        holder[0]=best;return selected;
    }
    private Map<String,Object> result(boolean ok,String message) {
        Map<String,Object> m=new LinkedHashMap<>();m.put("success",ok);m.put("message",message);return m;
    }
    public synchronized Map<String,Object> dashboard() {
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("incidents",sortedIncidents());m.put("units",unitList());m.put("locations",new TreeSet<>(roads.keySet()));
        m.put("pendingCount",queue.size());
        m.put("activeCount",units.values().stream().filter(u->!u.available).count());
        return m;
    }
    public synchronized List<Map<String,Object>> sortedIncidents() {
        List<Incident> list=new ArrayList<>(incidents.values());list.sort(PRIORITY);
        List<Map<String,Object>> out=new ArrayList<>();for(Incident i:list)out.add(i.json());return out;
    }
    public synchronized List<Map<String,Object>> unitList() {
        List<Unit> list=new ArrayList<>(units.values());list.sort(Comparator.comparing(u->u.id));
        List<Map<String,Object>> out=new ArrayList<>();for(Unit u:list)out.add(u.json());return out;
    }
    public synchronized Map<String,Object> report(Map<String,Object> body) throws SQLException {
        String location=String.valueOf(body.getOrDefault("location",""));
        String type=String.valueOf(body.getOrDefault("type",""));
        if(!roads.containsKey(location)) return result(false,"Unknown location.");
        if(!Set.of("Fire","Industrial Fire","Rescue","Accident").contains(type)) return result(false,"Invalid incident type.");
        int intensity=number(body,"intensity",1,10), population=number(body,"population",0,100000);
        boolean critical=Boolean.parseBoolean(String.valueOf(body.getOrDefault("critical","false")));
        if(intensity<1||population<0) return result(false,"Invalid severity or population.");
        counter++;
        Incident i=new Incident("I"+String.format("%02d",counter),location,type,intensity,population,critical,counter,"Pending","None");
        incidents.put(i.id,i);queue.add(i);persistAll();
        Map<String,Object> out=result(true,"Incident reported successfully.");out.put("incident",i.json());return out;
    }
    private int number(Map<String,Object> b,String key,int min,int max) {
        try {int n=Integer.parseInt(String.valueOf(b.get(key)));return n>=min&&n<=max?n:-1;}
        catch(Exception e){return -1;}
    }
    public synchronized Map<String,Object> dispatch() throws SQLException {
        if(queue.isEmpty()) return result(false,"No pending incidents.");
        Incident i=queue.poll();RouteResult[] holder=new RouteResult[1];Unit u=findNearestUnit(i,holder);
        if(u==null) {queue.add(i);return result(false,"No suitable unit is available or the incident is unreachable. Incident remains pending.");}
        RouteResult rr=holder[0];u.assign(i.id,rr.path);i.assignedUnit=u.id;i.status="En Route";
        try(Connection c=Database.getConnection();PreparedStatement p=c.prepareStatement(
            "INSERT INTO dispatch_history(incident_id,unit_id,route_text,travel_time) VALUES(?,?,?,?)")) {
            p.setString(1,i.id);p.setString(2,u.id);p.setString(3,String.join(" -> ",rr.path));p.setInt(4,rr.time);p.executeUpdate();
        }
        persistAll();
        Map<String,Object> out=result(true,"Unit "+u.id+" assigned.");out.put("incident",i.json());out.put("unit",u.json());out.put("route",rr.json());
        out.put("notification",u.type+" "+u.id+" has been assigned to your incident.");return out;
    }
    public synchronized Map<String,Object> updateIncident(Map<String,Object> body) throws SQLException {
        String id=String.valueOf(body.getOrDefault("id",""));Incident i=incidents.get(id);
        if(i==null)return result(false,"Incident not found.");
        if(!"Pending".equals(i.status))return result(false,"Only pending incidents can be reprioritized.");
        int intensity=number(body,"intensity",1,10), population=number(body,"population",0,100000);
        if(intensity<1||population<0)return result(false,"Invalid severity or population.");
        queue.remove(i);i.intensity=intensity;i.population=population;
        i.critical=Boolean.parseBoolean(String.valueOf(body.getOrDefault("critical","false")));queue.add(i);persistAll();
        return result(true,"Incident updated. Priority queue reorganized.");
    }
    public synchronized Map<String,Object> track(String id) throws SQLException {
        Unit u=units.get(id);if(u==null)return result(false,"Unit not found.");
        if(u.available)return result(false,"This unit is not assigned to an incident.");
        Incident i=incidents.get(u.incidentId);if(i==null)return result(false,"Assigned incident not found.");
        RouteResult rr=findRoute(u.location,i.location);if(rr==null)return result(false,"No reachable route. Check road closures.");
        u.route=new ArrayList<>(rr.path);u.routeIndex=0;
        if(u.location.equals(i.location)){u.status="Reached";i.status="Reached";persistAll();return result(true,"Assigned rescue unit has reached the incident location.");}
        if(u.route.size()<2)return result(false,"No next location is available.");
        u.location=u.route.get(1);u.routeIndex=1;
        if(u.location.equals(i.location)){u.status="Reached";i.status="Reached";}
        else {u.status="En Route";i.status="En Route";}
        persistAll();Map<String,Object> out=result(true,"Unit "+id+" moved to "+u.location+".");
        out.put("unit",u.json());out.put("incident",i.json());out.put("route",rr.json());return out;
    }
    public synchronized Map<String,Object> closeRoad(String a,String b) throws SQLException {
        if(!roads.containsKey(a)||!roads.containsKey(b))return result(false,"Unknown location.");
        boolean ab=roads.get(a).removeIf(r->r.to.equals(b));boolean ba=roads.get(b).removeIf(r->r.to.equals(a));
        if(!ab||!ba)return result(false,"No direct road exists between these locations.");
        List<String> updates=new ArrayList<>();
        for(Unit u:units.values()) if(!u.available) {
            Incident i=incidents.get(u.incidentId);if(i!=null) {
                RouteResult rr=findRoute(u.location,i.location);
                if(rr==null){u.route.clear();u.routeIndex=0;updates.add("No route currently available for "+u.id);}
                else {u.route=new ArrayList<>(rr.path);u.routeIndex=0;updates.add("New route for "+u.id+": "+String.join(" -> ",rr.path)+" (time "+rr.time+")");}
            }
        }
        persistAll();Map<String,Object> out=result(true,"Road closed between "+a+" and "+b+".");out.put("updates",updates);return out;
    }
    public synchronized Map<String,Object> complete(String id) throws SQLException {
        Unit u=units.get(id);if(u==null)return result(false,"Unit not found.");
        if(u.available)return result(false,"This unit is already available.");
        Incident i=incidents.get(u.incidentId);if(i==null)return result(false,"Assigned incident not found.");
        i.status="Completed";u.complete();persistAll();
        Map<String,Object> out=result(true,"Incident "+i.id+" completed. Unit "+u.id+" is now available.");
        out.put("notification","Incident response has been completed.");out.put("nextDispatch",dispatch());return out;
    }
    public synchronized Map<String,Object> route(String start,String end) {
        RouteResult rr=findRoute(start,end);
        if(rr==null)return result(false,"No reachable route exists.");
        Map<String,Object> out=result(true,"Fastest route found.");out.put("route",rr.json());return out;
    }
}
