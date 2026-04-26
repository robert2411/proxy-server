---
id: doc-4
title: Tech Stack and Dependencies
type: other
created_date: '2026-04-26 21:24'
---

# Tech Stack and Dependencies

Reference for the project's core technology choices and dependency versions.

## Runtime

| Component | Artifact | Version | Purpose |
|-----------|----------|---------|---------|
| Framework | spring-boot-starter-parent | 4.0.5 | Parent POM, dependency management |
| Web layer | spring-boot-starter-webflux | (managed) | Reactive HTTP server (Reactor Netty) |
| SSH client | com.hierynomus:sshj | 0.40.0 | SSH session management, local port forwarding, ssh_config parsing |
| Metrics | micrometer-registry-prometheus | (managed) | Prometheus metrics export |
| Management | spring-boot-starter-actuator | (managed) | Health checks, metrics endpoints |
| Boilerplate | org.projectlombok:lombok | (managed) | Annotation-driven code generation |

## Test

| Artifact | Version | Purpose |
|----------|---------|---------|
| spring-boot-starter-test | (managed) | JUnit 5, MockMvc, assertions |
| io.projectreactor:reactor-test | (managed) | Reactive stream testing utilities |
| com.squareup.okhttp3:mockwebserver | 4.12.0 | HTTP mock server for proxy handler tests |

## Build

- **Maven** with `spring-boot-maven-plugin` for executable JAR packaging
- Artifact: `Proxy-server-1.0-SNAPSHOT.jar`

## Configuration

- `application.yml` at `src/main/resources/`
- Server port: **8080**
- Actuator endpoints exposed: `health`, `prometheus`

## Entry Point

- `com.github.robert2411.ProxyServerApplication` — `@SpringBootApplication` main class

