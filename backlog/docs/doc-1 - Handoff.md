---
id: doc-1
title: Handoff
type: other
created_date: '2026-04-24 21:24'
---

Claude finished the response
You said: On my work i have the following setup:
On my work i have the following setup:
I have a host that builds up tunnels to other machines (ssh, with certificates and multiple hops, controlled trough a sshconfig file)
and a ui that connects directly to these tunnels

Now i want to make a new architecture:

A proxy (like nginx) that preferably manages these tunnels directly
The urls should then be <proxy host>:<proxy prort>/<target-host>/<target port>/<target url>

What is the best tool for this and how to set it up

11:11 PM
Claude responded: There's an important architectural decision hiding in your question: do you want the proxy to actually manage SSH tunnels (one process, SSH client built in), o…

Weighed custom proxy versus standard tools for SSH tunnel architecture
Weighed custom proxy versus standard tools for SSH tunnel architecture
There's an important architectural decision hiding in your question: do you want the proxy to actually manage SSH tunnels (one process, SSH client built in), or do you want to separate concerns (tunnels maintained externally, proxy is pure HTTP)? The answer changes the tool significantly, because no mainstream reverse proxy (nginx, HAProxy, Caddy, Traefik) speaks SSH natively. They all proxy HTTP/TCP over plain sockets.

Option A — Separate concerns (simplest, most boring)
Keep tunnels outside the proxy. Use autossh or systemd units to establish each SSH tunnel and bind to a local port. Then put nginx or Caddy in front to do pure path-based routing.

With your URL scheme /<host>/<port>/<rest>, Caddy is cleaner than nginx because the path matching syntax is nicer and dynamic upstreams don't require a resolver dance:

caddyfile
:8080 {
    @t path_regexp t ^/([^/]+)/([^/]+)/(.*)$
    handle @t {
        rewrite * /{re.t.3}
        reverse_proxy {re.t.1}:{re.t.2}
    }
}
Problem: the proxy needs DNS/network reachability to host:port. Since your tunnels bind to 127.0.0.1:<localport>, you need a mapping from (target-host, target-port) → 127.0.0.1:<localport>. Caddy can do this with a map, but you end up maintaining that table alongside your ssh_config. Annoying but workable.

The winning variant of Option A: run ssh -D 1080 for a dynamic SOCKS5 proxy from your bastion, and route the HTTP proxy through SOCKS. Then any target-host:target-port is resolved inside the SSH session — no per-tunnel local-port mapping. Caddy/nginx don't do SOCKS egress cleanly, but a small Go/Node HTTP proxy does it in ~50 lines. This is probably the cleanest "off-the-shelf-ish" path.

Option B — One process, SSH-aware proxy (what you actually described)
If you literally want "a proxy that manages these tunnels directly," you're building a small service. Good news: it's genuinely small, and it fits your stack perfectly.

Java / Spring Boot WebFlux + sshj — this is what I'd suggest given your background:

sshj (or Apache MINA SSHD client) handles SSH including multi-hop (ProxyJump-equivalent via nested DirectConnection / newDirectConnection), certificate auth, and ssh_config parsing (sshj has OpenSSHKnownHosts + config parsing helpers).
WebFlux's reactive HttpClient from Netty or a custom handler does the HTTP proxying.
You maintain a pool of SSHClient sessions keyed by target host; each request opens a DirectTCPIPChannel to target-host:target-port and streams the HTTP request/response through it.
Core flow per request:

Parse path → (targetHost, targetPort, remainingPath).
Look up or create an SSHClient for the bastion chain (from ssh_config).
Open a direct-tcpip channel: client.newDirectConnection(targetHost, targetPort).
Wrap the channel's input/output streams and pipe the HTTP exchange through it.
Reactor Netty is a bit awkward for "give me arbitrary bidirectional bytes on this socket", so many people do this with a plain servlet or blocking I/O on a thread pool. For a low-traffic dev/internal tool this is totally fine.

Go alternative — if you wanted to write this outside Java, golang.org/x/crypto/ssh + net/http/httputil.ReverseProxy with a custom Transport.DialContext is probably the most elegant version of this anywhere. ~100 lines. Worth knowing it exists.

My recommendation for your specific situation
Given you already own the host that builds the tunnels, the UI lives next to it, and you're comfortable with Spring Boot/WebFlux: build Option B as a small Spring Boot service. You get:

One deployable artifact, no autossh sidecars, no mapping table drift.
Native ssh_config reuse (sshj reads it).
Easy to add per-request auth, logging to Grafana, metrics to Prometheus — all stuff you already wire up daily.
URL scheme is whatever you want, including your <host>/<port>/<path> form.
The only reason to pick Option A instead is if you expect high traffic or want off-the-shelf HTTP features (HTTP/2 termination, gzip, caching) without writing them. For an internal tool fronting a UI, that's rarely the deciding factor.

One thing worth pinning down before you start: are the target services HTTP, or is some of this raw TCP (databases, AMQP brokers, etc.)? If it's mixed, your URL scheme doesn't work for non-HTTP, and you'd want a hybrid — HTTP path routing for web UIs, plus a separate TCP passthrough (different port, per-target) for everything else. Worth thinking through before the scheme locks in.

You said: the main trafic is http trafic, there are some use cases with amqp brokers but they can still be on a "private" tunnel as only 1 or 2 people use them while the…
the main trafic is http trafic, there are some use cases with amqp brokers but they can still be on a "private" tunnel as only 1 or 2 people use them while the ui is used by +/- 10 to 20 people

11:16 PM
Claude responded: Good — HTTP-dominant with low-volume AMQP as a separate path makes this clean.

Architected HTTP-focused Spring Boot proxy with sshj implementation
Architected HTTP-focused Spring Boot proxy with sshj implementation
Good — HTTP-dominant with low-volume AMQP as a separate path makes this clean. Leave the existing SSH tunnel setup in place for the AMQP users (no reason to force them through the new service), and build the HTTP proxy as its own Spring Boot service that owns its own SSH sessions.

Architecture
A Spring Boot WebFlux service using sshj that:

Reads your existing ~/.ssh/config so you don't maintain tunnel definitions twice.
Maintains a pool of SSH sessions, honoring ProxyJump chains.
For each unique (target-host, target-port) requested, lazily creates a local port forward to an ephemeral loopback port.
Proxies HTTP via WebClient to that local port.
The local-port-forward trick is the key move. It avoids writing raw HTTP over SSH channels and gives Netty a normal 127.0.0.1:<ephemeral> target — gzip, HTTP keepalive, chunked transfer all Just Work. You're essentially replicating your current autossh-style setup inside one process, but with lifecycle control, metrics, and on-demand creation.

Dependencies
xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
<dependency>
    <groupId>com.hierynomus</groupId>
    <artifactId>sshj</artifactId>
    <version>0.38.0</version>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
Core pieces
SSH session manager — parses ssh_config, resolves ProxyJump chains, caches clients:

java
@Component
public class SshSessionManager {
    private final OpenSSHConfig sshConfig;
    private final Map<String, SSHClient> clientsByHost = new ConcurrentHashMap<>();

    public SshSessionManager() throws IOException {
        File cfg = new File(System.getProperty("user.home"), ".ssh/config");
        this.sshConfig = OpenSSHConfig.parse(new FileReader(cfg));
    }

    public synchronized SSHClient clientFor(String targetHost) throws IOException {
        SSHClient existing = clientsByHost.get(targetHost);
        if (existing != null && existing.isConnected() && existing.isAuthenticated()) {
            return existing;
        }
        SSHClient client = buildChain(targetHost);
        clientsByHost.put(targetHost, client);
        return client;
    }

    private SSHClient buildChain(String targetHost) throws IOException {
        ConfigFile.Entry entry = sshConfig.getConfig(targetHost);
        // If entry.getProxyJump() is set, recurse: build the jump client first,
        // then target.connectVia(jumpClient.newDirectConnection(host, port)).
        // Load IdentityFile(s), apply User, Port, HostName overrides from the entry.
        // ...
    }
}
The buildChain method is where the real work lives — the recursion for ProxyJump plus identity/cert loading. sshj exposes everything you need through ConfigFile.Entry, and for cert auth you'll use OpenSSHKeyV1KeyFile with the cert placed next to the private key under the -cert.pub naming convention (sshj picks it up automatically).

Port forward cache:

java
@Component
public class PortForwardCache {
    private final SshSessionManager sessions;
    private final Map<String, Integer> forwards = new ConcurrentHashMap<>();

    public int localPortFor(String targetHost, int targetPort) throws IOException {
        return forwards.computeIfAbsent(
            targetHost + ":" + targetPort,
            k -> openForward(targetHost, targetPort));
    }

    private int openForward(String targetHost, int targetPort) {
        SSHClient client = sessions.clientFor(targetHost);
        ServerSocket ss = new ServerSocket(0, 0, InetAddress.getLoopbackAddress());
        int localPort = ss.getLocalPort();
        LocalPortForwarder.Parameters p =
            new LocalPortForwarder.Parameters("127.0.0.1", localPort, targetHost, targetPort);
        LocalPortForwarder fwd = client.newLocalPortForwarder(p, ss);
        forwarderExecutor.submit(() -> { try { fwd.listen(); } catch (IOException ignored) {} });
        return localPort;
    }
}
Routing handler:

java
@Component
@RequiredArgsConstructor
public class ProxyHandler {
    private final PortForwardCache forwards;
    private final WebClient webClient = WebClient.builder().build();

    public Mono<ServerResponse> proxy(ServerRequest req) {
        String host = req.pathVariable("host");
        int port = Integer.parseInt(req.pathVariable("port"));
        String rest = req.path().replaceFirst("^/" + host + "/" + port, "");
        String query = req.uri().getRawQuery();

        int localPort = forwards.localPortFor(host, port);
        URI upstream = URI.create("http://127.0.0.1:" + localPort + rest
                + (query != null ? "?" + query : ""));

        return webClient.method(req.method())
            .uri(upstream)
            .headers(h -> {
                h.addAll(req.headers().asHttpHeaders());
                h.set(HttpHeaders.HOST, host + ":" + port); // rewrite Host
            })
            .body(BodyInserters.fromDataBuffers(req.bodyToFlux(DataBuffer.class)))
            .exchangeToMono(resp -> ServerResponse.status(resp.statusCode())
                .headers(h -> h.addAll(resp.headers().asHttpHeaders()))
                .body(resp.bodyToFlux(DataBuffer.class), DataBuffer.class));
    }
}
Route it with:

java
@Bean
RouterFunction<ServerResponse> routes(ProxyHandler h) {
    return RouterFunctions.route(RequestPredicates.path("/{host}/{port}/**"), h::proxy);
}
Things that will bite you if you skip them
SSH keepalive and reconnect. Sessions die silently on NAT timeouts. Configure client.getConnection().getKeepAlive().setKeepAliveInterval(30) and wrap clientFor() to reconnect on detected failure (plus invalidate the associated port forwards in the cache).

Bounded executor for forwarders. Each LocalPortForwarder.listen() blocks a thread. Use a bounded pool sized to the number of distinct targets you expect, not newCachedThreadPool.

Host header rewrite. Shown above. Many backends (especially Spring services behind their own filters) reject requests whose Host doesn't match what they expect.

WebSocket upgrade. If the UI uses WS for anything (live updates, push), plain WebClient won't proxy it — you'd need ReactorNettyWebSocketClient bridged to server-side. Worth checking before you commit. For most JSON-over-HTTP UIs this is a non-issue.

Path parsing. {host}/{port}/** with PathPatternParser is fine, but watch for URL-encoded slashes in the ** tail if your upstreams have opinions about them — Netty may decode them differently than the upstream wants.

Packaging
Dockerize with ~/.ssh/config, keys, and certs mounted read-only. Expose 8080, metrics at /actuator/prometheus. Given your existing Grafana/Prometheus stack, the obvious counters are proxy_requests_total{target,status}, ssh_sessions_active, port_forwards_active, and a histogram of upstream latency — five minutes of wiring and you have the full observability story the autossh setup never had.

One thing worth deciding early: do you want auth on the proxy itself (only logged-in UI users can hit /target-host/.../), or is the proxy trusted because it only listens on an internal network? If the former, a Spring Security filter in front of the router is the natural spot — and it also solves "user X is allowed to see target Y but not target Z" cleanly, which a pure nginx setup makes awkward.
