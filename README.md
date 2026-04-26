# SSH Proxy Server

A Spring Boot WebFlux service that proxies HTTP requests through SSH port forwards.
Requests are routed via `/{host}/{port}/{path}` — the service resolves the SSH
connection, opens a local port forward, and streams the HTTP request/response
to the upstream backend.

## Prerequisites

- Java 21+
- Maven 3.9+
- SSH config at `~/.ssh/config` with target host entries
- SSH keys and certificates in `~/.ssh/`

## Build & Run

```bash
mvn clean package
java -jar target/Proxy-server-1.0-SNAPSHOT.jar
```

The proxy listens on port 8080.

## Docker

### Build

```bash
docker build -t proxy-server .
```

### Run

SSH keys and config **must be mounted read-only** at runtime — they are never
baked into the image.

```bash
docker run -v ~/.ssh:/root/.ssh:ro -p 8080:8080 proxy-server
```

### Docker Compose

```bash
docker-compose up
```

The `docker-compose.yml` maps port 8080, mounts `~/.ssh` read-only, and
configures a healthcheck against `/actuator/health`.

## Actuator Endpoints

| Endpoint              | Description                        |
|-----------------------|------------------------------------|
| `/actuator/health`    | Health status including SSH sessions |
| `/actuator/prometheus`| Prometheus metrics scrape endpoint |

## Metrics

The following Micrometer metrics are exposed via `/actuator/prometheus`:

- `proxy_requests_total{target, status}` — proxied request count
- `ssh_sessions_active` — live SSH session gauge
- `port_forwards_active` — active port forward gauge
- `proxy_upstream_latency_seconds` — upstream HTTP latency histogram
- `ssh_reconnects_total{target}` — SSH reconnection counter

## Configuration

| Property                        | Default | Description                     |
|---------------------------------|---------|---------------------------------|
| `server.port`                   | 8080    | HTTP listen port                |
| `proxy.ssh.forwarder-threads`   | 16      | Max port-forward threads        |
| `ssh.enabled`                   | true    | Enable/disable SSH component    |
