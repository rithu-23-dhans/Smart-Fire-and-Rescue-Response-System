# Smart Fire and Rescue Response System — Town of Sudar

A complete, production-grade emergency response web application designed for the town of Sudar. The system prioritizes competing emergency incidents, handles unit equipment compatibility, dynamically computes shortest travel times using Dijkstra's algorithm, manages real-time road closures/reopenings with rerouting, and maintains persistent state via MySQL.

---

## 1. Technology Stack
- **Frontend**: Clean, responsive HTML5, CSS3, and vanilla JavaScript (no React, Node.js, or external frameworks).
- **Backend**: Java 17+, Jakarta Servlet API 6.0 (`jakarta.servlet.*`), JDBC.
- **Database**: MySQL 8+ (InnoDB engine with transaction support).
- **Build & Packaging**: Apache Maven 3.9+, WAR deployment on Apache Tomcat 10.1+.
- **Security**: PBKDF2WithHmacSHA256 salted password hashing, server-side `HttpSession` management, and cryptographically random public tracking tokens (`tr_...`).

---

## 2. Project Architecture & Scope
- **Scenario**: The town of Sudar is served by:
  - **2 Stations**: `Station1` and `Station2`.
  - **4 Response Units**:
    - `FE01` (Fire Engine, Station1) — Capabilities: `Fire`, `Industrial Fire`
    - `FE02` (Fire Engine, Station1) — Capabilities: `Fire`
    - `RV01` (Rescue Van, Station2) — Capabilities: `Rescue`, `Accident`
    - `RV02` (Rescue Van, Station2) — Capabilities: `Rescue`, `Accident`
  - **12 Connected Locations**: `Station1`, `Station2`, `TownCenter`, `ResidentialArea`, `IndustrialArea`, `Hospital`, `School`, `Market`, `RiverBridge`, `Warehouse`, `AirportRoad`, `OldTown`.
  - **Seeded Incidents**: 15 realistic initial incidents (`I01` to `I15`) seeded automatically upon first launch.

---

## 3. Preserved Java Algorithms & Decision Rules
1. **Priority Queue (`PriorityQueue<Incident>`)**:
   Pending emergencies are ordered strictly according to the 4-tier comparator:
   - **Rule 1**: Higher intensity first ($10 \rightarrow 1$).
   - **Rule 2**: Critical infrastructure affected before non-critical incidents.
   - **Rule 3**: Higher population exposure rank first.
   - **Rule 4**: Earlier arrival order first (FIFO tie-breaker).
2. **Dijkstra Shortest Travel-Time Pathfinding**:
   Evaluates undirected road graph edges. Closed roads are immediately bypassed during path calculation.
3. **Nearest Compatible Unit Dispatch**:
   Dispatches the closest reachable unit possessing the required capability (`canHandle(incidentType)`). Prevents concurrent double-assignments.
4. **Step-by-Step Waypoint Tracking**:
   Each `Track` step advances the assigned unit forward by one road segment toward the incident site, automatically updating the status to `Reached` upon arrival.
5. **Road Closures & Dynamic Rerouting**:
   Closing or reopening a road recalculates routes in real-time for any units currently en route.
6. **Mission Completion**:
   Frees the response unit back to `Available`, closes the incident, and automatically triggers dispatch for the next pending incident.

---

## 4. Public vs. Admin Interfaces

### Public Citizen Portal (`index.html`) — No Login Required
- **Report Emergency**: Immediate reporting with location, incident type, severity slider (1–10), people affected, critical infrastructure flag, and situation description. Generates a unique **Incident Reference ID** (e.g. `I16`) and **Secure Tracking Token** (e.g. `tr_9f3b7c2a`).
- **Track Incident & Unit**: Enter Reference ID + Tracking Token to view real-time status (`Pending`, `En Route`, `Reached`, `Completed`), assigned unit details, current waypoint, visual route breadcrumbs, and timeline history.
- **Citizen Situation Updates**: Submit observations or notes to active incidents.
- **Find Fastest Route**: Public tool to calculate quickest travel times between any Sudar locations.

### Admin Operations Console (`admin.html`) — Authenticated
- **Secure Authentication**: Salted PBKDF2 password hash validation and server-side session protection.
- **Default Credentials**:
  - **Username**: `admin`
  - **Password**: `Admin@123`
- **Real-Time KPIs**: Live counts of pending queue, active missions, completed incidents, fleet availability, and closed roads.
- **Priority Dispatch**: Inspect ordered queue, trigger auto-dispatch, perform manual dispatch to specific units, or reprioritize pending incidents.
- **Fleet Tracking**: Advance units step-by-step and complete missions.
- **Master Incident Registry**: Searchable and filterable table across all incidents with full audit timeline inspection.
- **Road Management**: Instant toggles to **Close** or **Reopen** road connections with reroute notifications.

---

## 5. Setup & Deployment Instructions

### Prerequisites
1. **JDK 17 or higher** (Adoptium / Oracle).
2. **Apache Maven 3.9+**.
3. **MySQL Server 8.0+**.
4. **Apache Tomcat 10.1+** (compatible with Jakarta Servlet 6.0).

### Step 1: Database Setup
1. Start your local MySQL service.
2. Run the provided schema script in MySQL:
   ```sql
   SOURCE database/schema.sql;
   ```
3. Check and update database credentials in `backend/src/main/resources/db.properties`:
   ```properties
   db.url=jdbc:mysql://localhost:3306/fire_rescue?useSSL=false&serverTimezone=Asia/Kolkata&allowPublicKeyRetrieval=true
   db.username=root
   db.password=your_password_here
   ```

### Step 2: Build Application
In the `backend` directory, run:
```bash
mvn clean package
```
All unit and integration tests will execute automatically. Upon completion, `target/FireRescueSystem.war` is generated.

### Step 3: Deploy to Tomcat 10.1+
1. Copy `backend/target/FireRescueSystem.war` into Tomcat's `webapps/` folder.
2. Start Tomcat (`bin/startup.bat` on Windows or `bin/startup.sh` on Linux).
3. Access the portals:
   - **Public Portal**: `http://localhost:8080/FireRescueSystem/`
   - **Admin Console**: `http://localhost:8080/FireRescueSystem/admin.html`

### Step 4: Run Tests
To execute the automated test suite at any time:
```bash
mvn test
```
