# Smart Fire and Rescue Response System

Stack: HTML/CSS/JavaScript, Java Servlets (Jakarta Servlet 6), JDBC, MySQL 8, Maven WAR, Tomcat 10.1+.

## Setup
1. Install JDK 17+, Maven, MySQL 8+, and Apache Tomcat 10.1+.
2. Create the database:
   ```sql
   SOURCE /absolute/path/to/FireRescueSystem/database/schema.sql;
   ```
3. Edit `backend/src/main/resources/db.properties` with your MySQL username/password.
4. In the project root, run `mvn clean package`.
5. Copy `backend/target/FireRescueSystem.war` to Tomcat's `webapps` directory and start Tomcat.
6. Open `http://localhost:8080/FireRescueSystem/`.
7. The first start seeds the original road network, four response units, and the same 15 sample incidents. Subsequent starts load persisted database state rather than re-seeding sample incidents.

## Notes
- Tomcat 10.1+ uses `jakarta.servlet.*`. Do not deploy on Tomcat 9 without changing servlet dependencies/imports.
- The application is intended for a local mini-project/demo. Before public deployment, add authentication/authorization, CSRF protection, stricter audit controls, and production secrets management.
- The route graph is undirected and its initial edge travel times match the provided Java code.
- Tracking is the original manual simulation: each Track action advances one road segment; it is not GPS tracking.
- This starter keeps the core data structures/decision rules (PriorityQueue, Comparator, HashMap, HashSet, Dijkstra, compatible-nearest available unit, one unit per incident, pending if no unit, explicit mission completion). The service code is adapted from the supplied console program for web requests.
