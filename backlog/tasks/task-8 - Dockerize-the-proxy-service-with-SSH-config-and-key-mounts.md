---
id: TASK-8
title: Dockerize the proxy service with SSH config and key mounts
status: To Do
assignee: []
created_date: '2026-04-24 21:27'
updated_date: '2026-04-26 22:04'
labels: []
milestone: m-2
dependencies:
  - TASK-5
priority: medium
ordinal: 3000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Package the service as a Docker image. SSH config, private keys, and certificates must be mounted read-only at runtime — never baked into the image.

Design decisions:
- Multi-stage build: Maven build stage + slim JRE runtime stage (eclipse-temurin:21-jre or similar)
- Mount points: /root/.ssh/config, /root/.ssh/ (keys + certs) — read-only mounts
- Expose port 8080 (HTTP proxy) and 8081 or same port for actuator (or use management.server.port)
- Document recommended docker run / compose snippet in README or inline in Dockerfile comments
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 docker build produces an image without SSH keys or config baked in
- [ ] #2 Container starts with -v ~/.ssh:/root/.ssh:ro mount and proxy handles requests correctly
- [ ] #3 Image is based on a slim JRE (not JDK) runtime layer
- [ ] #4 Actuator health endpoint reachable from host after docker run
- [ ] #5 docker-compose.yml example (or equivalent) documents volume mount and port mapping
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Create multi-stage Dockerfile
   - File: Dockerfile (project root)
   - Stage 1 (build):
     FROM maven:3.9-eclipse-temurin-21 AS build
     WORKDIR /app
     COPY pom.xml .
     RUN mvn dependency:go-offline -B
     COPY src ./src
     RUN mvn -B package -DskipTests
   - Stage 2 (runtime):
     FROM eclipse-temurin:21-jre AS runtime
     WORKDIR /app
     RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
     COPY --from=build /app/target/Proxy-server-1.0-SNAPSHOT.jar app.jar
     EXPOSE 8080
     ENTRYPOINT ["java", "-jar", "app.jar"]
   - JAR filename: derived from pom.xml artifactId=Proxy-server + version=1.0-SNAPSHOT → Proxy-server-1.0-SNAPSHOT.jar. Do NOT use a *.jar glob because Spring Boot Maven plugin produces both the repackaged JAR and a .jar.original file; a glob matching multiple files to a single COPY destination fails the Docker build.
   - AC#3: eclipse-temurin:21-jre is a slim JRE runtime (not JDK)
   - curl is installed in the runtime stage for healthcheck use (see step 4)

2. Ensure no SSH keys/config baked into image
   - Do NOT COPY any .ssh directory in Dockerfile
   - Create .dockerignore file to exclude sensitive and unnecessary files:
     File: .dockerignore (project root)
     Contents:
       .ssh/
       *.pem
       *.key
       id_rsa*
       id_ed25519*
       target/
       .git/
       .idea/
       *.iml
   - AC#1: verified by absence of COPY .ssh and presence of .dockerignore

3. Document mount points in Dockerfile comments and README
   - Add comments in Dockerfile explaining the SSH mount pattern:
     # SSH config and keys must be mounted at runtime: -v ~/.ssh:/root/.ssh:ro
     # Never bake SSH credentials into the image.
   - The SshSessionManager reads from ~/.ssh/config which maps to /root/.ssh/config inside container
   - Verify application.yml does not hardcode absolute host paths for SSH config

4. Create docker-compose.yml
   - File: docker-compose.yml (project root)
   - Contents:
     services:
       proxy:
         build: .
         ports:
           - "8080:8080"
         volumes:
           - ~/.ssh:/root/.ssh:ro
         healthcheck:
           test: ["CMD", "curl", "-sf", "http://localhost:8080/actuator/health"]
           interval: 30s
           timeout: 10s
           retries: 3
           start_period: 15s
   - Healthcheck decision: use curl (installed in step 1 runtime stage). curl is the concrete choice — no wget fallback. The -sf flags suppress output and fail on HTTP errors.
   - AC#5: docker-compose.yml documents volume mounts and port mappings
   - AC#4: actuator health is on port 8080 (same port as proxy, no separate management port), compose healthcheck verifies reachability

5. Verify actuator health is reachable from host
   - Actuator is on same port (8080) per application.yml — no separate management.server.port configured
   - /actuator/health exposed via management.endpoints.web.exposure.include: health,prometheus
   - Container exposes 8080 → host can reach http://localhost:8080/actuator/health
   - AC#4: satisfied by port mapping + actuator config already in place

6. Test the Docker build
   - Manual verification steps:
     docker build -t proxy-server .
     docker run --rm proxy-server ls /root/.ssh → should show empty or "No such file" (proves no baked-in keys, AC#1)
     docker run --rm -v ~/.ssh:/root/.ssh:ro -p 8080:8080 proxy-server → starts with SSH mount (AC#2)
     curl http://localhost:8080/actuator/health → returns {"status":"UP"} (AC#4)
   - Verify the specific JAR was copied: docker run --rm proxy-server ls /app/ → should show only app.jar

7. Update README.md with Docker usage
   - Add section: ## Docker
   - Document:
     - Build: docker build -t proxy-server .
     - Run: docker run -v ~/.ssh:/root/.ssh:ro -p 8080:8080 proxy-server
     - Compose: docker-compose up
   - Note: SSH keys and config must be mounted read-only at runtime, never baked into the image
   - Note: If the project version changes from 1.0-SNAPSHOT, update the JAR filename in the Dockerfile COPY step accordingly (or consider using maven-jar-plugin finalName to stabilize the output filename)
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Self-review complete. Plan covers all 5 ACs:
- AC#1: .dockerignore + no COPY .ssh → no keys baked in
- AC#2: docker-compose.yml documents -v ~/.ssh:/root/.ssh:ro mount
- AC#3: eclipse-temurin:21-jre runtime stage (not JDK)
- AC#4: Port 8080 exposed, actuator on same port, healthcheck in compose
- AC#5: docker-compose.yml with volume mount and port mapping
Dependency note: TASK-8 depends on TASK-5 (pom.xml + app structure) which is done. It does NOT strictly depend on TASK-6/TASK-7 but benefits from them (actuator health in healthcheck). Recommend implementing TASK-8 last in M3.
Analysis complete. Plan ready. No blockers.

🔍 PLAN REVIEW CONCERNS:
- Concern #1 (Build will break): Step 1 uses "COPY --from=build /app/target/*.jar app.jar" but Spring Boot Maven plugin produces BOTH a repackaged JAR and a .jar.original file in target/. Docker COPY with a glob matching multiple files to a single destination file will fail. Fix: use a specific filename (e.g. COPY --from=build /app/target/Proxy-server-1.0-SNAPSHOT.jar app.jar) or use a two-step approach: COPY --from=build /app/target/*.jar /app/ then ENTRYPOINT referencing the specific jar, or use spring-boot:repackage classifier to produce a predictable name.
- Concern #2 (Healthcheck tool not settled): Step 4 uses curl in the docker-compose healthcheck, but step 6 notes eclipse-temurin:21-jre may not include curl and suggests wget or CMD-SHELL as alternatives without committing to one. The plan must pick a single approach. Recommended: use wget which is typically available in Debian-based images, or better yet avoid installing extra tools and use a Spring Boot actuator startup probe approach, e.g. test: ["CMD-SHELL", "java -cp app.jar org.springframework.boot.loader.tools.MainClassFinder || wget -qO- http://localhost:8080/actuator/health || exit 1"]. Simplest fix: commit to the wget CMD-SHELL variant from step 6 and update step 4 to match.

Verdict: Plan needs two fixes — JAR copy glob and healthcheck tool decision. Rest of the plan (multi-stage build, .dockerignore, compose structure, README section) is solid and covers all 5 ACs.

Plan revised to address Plan Reviewer concerns:
- FIXED Concern #1: Step 1 now uses explicit JAR filename COPY --from=build /app/target/Proxy-server-1.0-SNAPSHOT.jar app.jar (derived from pom.xml artifactId+version). Glob removed. Rationale documented: Spring Boot Maven plugin produces both .jar and .jar.original, glob to single destination fails Docker build.
- FIXED Concern #2: Concrete decision made — use curl. Step 1 runtime stage installs curl via apt-get. Step 4 docker-compose.yml healthcheck uses curl -sf consistently. No more ambiguity between curl/wget. wget references removed entirely.
Self-review complete. All 5 ACs covered. No blockers.

✅ PLAN APPROVED — plan is complete, all AC covered, no ambiguity
- Steps verified: 7
- AC mapped: 5/5
- Concern #1 RESOLVED: Step 1 now uses explicit COPY --from=build /app/target/Proxy-server-1.0-SNAPSHOT.jar app.jar. Verified against pom.xml: artifactId=Proxy-server, version=1.0-SNAPSHOT. Glob removed with rationale documented.
- Concern #2 RESOLVED: Concrete decision — curl installed via apt-get in runtime stage. Healthcheck uses curl -sf consistently. No ambiguity between curl/wget.
- Cross-checked: pom.xml has spring-boot-maven-plugin (repackages JAR); application.yml serves actuator on port 8080; no management.server.port override.
<!-- SECTION:NOTES:END -->
