---
id: TASK-1
title: Bootstrap Spring Boot project with WebFlux and sshj dependencies
status: Done
assignee:
  - '@myself'
created_date: '2026-04-24 21:26'
updated_date: '2026-04-26 21:27'
labels: []
milestone: m-0
dependencies: []
priority: high
ordinal: 1000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Set up the Maven project so subsequent tasks have a working Spring Boot WebFlux base with all required dependencies declared. The project already has a skeleton pom.xml and Main.java — this task completes the dependency baseline.

Tech decisions (from design discussion):
- Spring Boot WebFlux (Reactor Netty) for async HTTP proxy
- sshj 0.40.0 for SSH session management and local port forwarding
- Micrometer + Prometheus registry for metrics (wired in M3 but declare dep now)
- Lombok to reduce boilerplate
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 pom.xml includes spring-boot-starter-webflux
- [x] #2 pom.xml includes com.hierynomus:sshj:0.40.0
- [x] #3 pom.xml includes micrometer-registry-prometheus
- [x] #4 pom.xml includes spring-boot-starter-actuator
- [x] #5 `mvn package -DskipTests` produces a runnable jar
- [x] #6 Application starts and responds to GET /actuator/health with 200
- [x] #7 Spring Boot version is 4.0.5 or newer
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Add Spring Boot parent POM: <parent> org.springframework.boot:spring-boot-starter-parent:4.0.5 </parent>
2. Add <dependency> org.springframework.boot:spring-boot-starter-webflux (no version — inherited from parent)
3. Add <dependency> com.hierynomus:sshj:0.40.0
4. Add <dependency> io.micrometer:micrometer-registry-prometheus (no version — managed by Boot)
5. Add <dependency> org.springframework.boot:spring-boot-starter-actuator
6. Add <dependency> org.projectlombok:lombok (scope provided)
7. Add spring-boot-maven-plugin to <build><plugins> for executable jar packaging
8. Replace src/main/java/com/github/robert2411/Main.java with a proper Spring Boot Application class:
   - Rename to ProxyServerApplication.java (same package com.github.robert2411)
   - Annotate with @SpringBootApplication
   - main() calls SpringApplication.run(ProxyServerApplication.class, args)
9. Create src/main/resources/application.yml with:
   - server.port: 8080
   - management.endpoints.web.exposure.include: health,prometheus
10. Delete the old Main.java file
11. Verify: run `mvn package -DskipTests` — must produce target/Proxy-server-1.0-SNAPSHOT.jar
12. Verify: run the jar, GET http://localhost:8080/actuator/health returns 200 {"status":"UP"}
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Analysis complete. Plan ready. No blockers. Self-review: all 7 AC mapped — parent version covers AC#7, each dependency covers AC#1-4, mvn package covers AC#5, actuator health covers AC#6. No unverified assumptions.

✅ PLAN APPROVED — plan is complete, all AC covered, no ambiguity
- Steps verified: 12
- AC mapped: 7/7
- Verified against actual project state: pom.xml is skeleton (no parent, no deps), Main.java is IntelliJ template. Plan correctly addresses both.
- Jar name in step 11 matches artifactId+version in pom.xml ✓

- Implemented Spring Boot 4.0.5 parent POM with all required dependencies
- Created ProxyServerApplication.java with @SpringBootApplication
- Created application.yml with actuator endpoints
- Added context loads test
- mvn package produces runnable jar successfully
- Actuator /health returns 200 {"status":"UP"}

Implementation complete. Ready for QA.

✅ QA APPROVED — all tests passing, no regressions
- AC/DoD: Complete (7/7 AC checked; no DoD items defined)
- Verification performed: `mvn test` ✅, `mvn package -DskipTests` ✅, `java -jar target/Proxy-server-1.0-SNAPSHOT.jar` + `GET /actuator/health` returned 200 ✅
- Code quality: Good (clean bootstrap class, minimal and clear config, no duplication found)
- Security: No issues found (no secrets, no risky input handling introduced in this bootstrap scope)
- Spelling/Docs: Clean

✅ SECURITY APPROVED — static audit complete, zero vulnerabilities identified
- Files reviewed: pom.xml, ProxyServerApplication.java, application.yml, ProxyServerApplicationTest.java
- Checks: OWASP Top 10, path traversal, ReDoS, input validation, credential exposure
- Notes: Actuator exposure limited to health+prometheus (good). No secrets in config. Clean bootstrap.

✅ DOCUMENTATION COMPLETE
- Created: backlog/docs/doc-4 - Tech-Stack-and-Dependencies.md (reference for project dependencies, versions, config, and entry point)
- Created: backlog/decisions/decision-3 - Spring-Boot-WebFlux-with-sshj-for-SSH-aware-HTTP-proxy.md (architectural decision: Option B single-process SSH-aware proxy over Option A separate concerns)

Squash dry-run output:
Nothing to squash.

✅ COMMIT COMPLETE: task-1: Bootstrap Spring Boot project with WebFlux and sshj dependencies
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Bootstrapped Spring Boot 4.0.5 WebFlux project with all required dependencies.

Changes:
- pom.xml: Added spring-boot-starter-parent 4.0.5, spring-boot-starter-webflux, sshj 0.40.0, micrometer-registry-prometheus, spring-boot-starter-actuator, lombok, spring-boot-starter-test, reactor-test, and spring-boot-maven-plugin
- src/main/java/com/github/robert2411/ProxyServerApplication.java: New Spring Boot application class
- src/main/resources/application.yml: Server port 8080, actuator health+prometheus exposed
- src/test/java/com/github/robert2411/ProxyServerApplicationTest.java: Context loads test
- Removed old Main.java template

Tests:
- 1 integration test (contextLoads) passing
- mvn package produces executable jar
- Actuator health responds 200
<!-- SECTION:FINAL_SUMMARY:END -->
