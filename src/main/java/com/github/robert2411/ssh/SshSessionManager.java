package com.github.robert2411.ssh;

import jakarta.annotation.PreDestroy;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.connection.channel.direct.DirectConnection;

import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Core SSH lifecycle component. Reads ~/.ssh/config via custom parser,
 * resolves ProxyJump hops recursively, handles certificate auth, and maintains
 * a ConcurrentHashMap cache of live SSHClient sessions keyed by target host.
 *
 * <p>Features:
 * <ul>
 *   <li>Keepalive: 30s interval on all sessions to prevent NAT timeout</li>
 *   <li>Automatic reconnect: stale sessions are transparently replaced</li>
 *   <li>Eviction listener: notifies PortForwardCache when sessions die</li>
 *   <li>Background health check: proactively evicts dead sessions every 60s</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "ssh.enabled", havingValue = "true", matchIfMissing = true)
public class SshSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SshSessionManager.class);
    private static final int KEEPALIVE_INTERVAL_SECONDS = 30;
    private static final int HEALTH_CHECK_INTERVAL_SECONDS = 60;

    private final SshConfigParser sshConfig;
    private final ConcurrentHashMap<String, SSHClient> sessionCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService healthCheckExecutor;
    private volatile PortForwardEvictionListener evictionListener;
    private volatile BiConsumer<String, SSHClient> reconnectListener;

    public SshSessionManager() {
        this(new File(System.getProperty("user.home"), ".ssh/config"));
    }

    /**
     * Package-private constructor for testing with custom config file path.
     */
    SshSessionManager(File configFile) {
        try {
            this.sshConfig = SshConfigParser.parse(configFile);
            log.info("Loaded SSH config from {}", configFile.getAbsolutePath());
        } catch (FileNotFoundException e) {
            log.error("SSH config not found at {}. Ensure the file exists and is readable. "
                    + "SshSessionManager cannot operate without it.", configFile.getAbsolutePath());
            throw new BeanCreationException("SshSessionManager",
                    "SSH config not found: " + configFile.getAbsolutePath(), e);
        } catch (IOException e) {
            log.error("Failed to parse SSH config at {}: {}", configFile.getAbsolutePath(), e.getMessage());
            throw new BeanCreationException("SshSessionManager",
                    "Failed to parse SSH config: " + e.getMessage(), e);
        }
        this.healthCheckExecutor = startHealthCheck();
    }

    /**
     * Package-private constructor for testing with pre-parsed config.
     */
    SshSessionManager(SshConfigParser config) {
        this(config, true);
    }

    /**
     * Package-private constructor for testing — allows disabling health check.
     */
    SshSessionManager(SshConfigParser config, boolean enableHealthCheck) {
        this.sshConfig = config;
        this.healthCheckExecutor = enableHealthCheck ? startHealthCheck() : null;
        log.info("SshSessionManager initialized with provided config");
    }

    /**
     * Register an eviction listener to be notified when sessions are evicted.
     * Used by PortForwardCache (TASK-4) to invalidate associated port forwards.
     *
     * @param listener the listener to register (null to unregister)
     */
    public void setEvictionListener(PortForwardEvictionListener listener) {
        this.evictionListener = listener;
    }

    /**
     * Register a reconnect listener to be notified when a stale session is replaced.
     * Used by ProxyMetrics to count reconnection events.
     *
     * @param listener the listener to register (host, newClient) → called on reconnect
     */
    public void setReconnectListener(BiConsumer<String, SSHClient> listener) {
        this.reconnectListener = listener;
    }

    /**
     * Returns a connected and authenticated SSHClient for the given target host.
     * Uses the session cache for efficiency; stale sessions are evicted and
     * reconnected transparently.
     *
     * @param targetHost the SSH host alias or hostname as defined in ssh_config
     * @return a connected and authenticated SSHClient
     * @throws IOException if connection or authentication fails
     */
    public SSHClient clientFor(String targetHost) throws IOException {
        try {
            return sessionCache.compute(targetHost, (key, existing) -> {
                if (existing != null && existing.isConnected() && existing.isAuthenticated()) {
                    return existing;
                }
                boolean isReconnect = existing != null;
                if (isReconnect) {
                    evictSession(key, existing);
                }
                try {
                    SSHClient newClient = buildClient(key);
                    if (isReconnect) {
                        BiConsumer<String, SSHClient> listener = this.reconnectListener;
                        if (listener != null) {
                            try {
                                listener.accept(key, newClient);
                            } catch (Exception ex) {
                                log.debug("Reconnect listener threw exception for {}: {}", key, ex.getMessage());
                            }
                        }
                    }
                    return newClient;
                } catch (IOException e) {
                    log.warn("Reconnect to {} failed: {}", key, e.getMessage());
                    throw new CompletionException(e);
                }
            });
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("Failed to establish SSH session to " + targetHost, e.getCause());
        }
    }

    /**
     * Evicts a session from the cache (public API for external eviction triggers).
     */
    public void evict(String targetHost) {
        SSHClient removed = sessionCache.remove(targetHost);
        if (removed != null) {
            evictSession(targetHost, removed);
        }
    }

    /**
     * Returns the number of cached sessions (for metrics/testing).
     */
    public int cacheSize() {
        return sessionCache.size();
    }

    /**
     * Returns all cached host keys (for keepalive iteration).
     */
    public Iterable<String> cachedHosts() {
        return sessionCache.keySet();
    }

    /**
     * Returns the status (connected and authenticated) of each cached SSH session.
     * Used by the health indicator to report per-host status without leaking SSHClient references.
     *
     * @return map of host → true if connected and authenticated, false otherwise
     */
    public Map<String, Boolean> sessionStatus() {
        Map<String, Boolean> status = new ConcurrentHashMap<>();
        for (Map.Entry<String, SSHClient> entry : sessionCache.entrySet()) {
            SSHClient client = entry.getValue();
            status.put(entry.getKey(), client.isConnected() && client.isAuthenticated());
        }
        return status;
    }

    @PreDestroy
    void shutdown() {
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdownNow();
            log.info("SSH session health check executor shut down");
        }
        // Close all cached sessions
        sessionCache.forEach((host, client) -> closeQuietly(client));
        sessionCache.clear();
    }

    // --- Private methods ---

    private ScheduledExecutorService startHealthCheck() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ssh-health-check");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::runHealthCheck,
                HEALTH_CHECK_INTERVAL_SECONDS, HEALTH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("SSH session health check scheduled every {}s", HEALTH_CHECK_INTERVAL_SECONDS);
        return executor;
    }

    /**
     * Background health check: iterates all cached sessions and evicts dead ones.
     * Uses remove(key, value) to avoid race with concurrent reconnects.
     */
    void runHealthCheck() {
        int evicted = 0;
        for (Map.Entry<String, SSHClient> entry : sessionCache.entrySet()) {
            String host = entry.getKey();
            SSHClient client = entry.getValue();
            if (!client.isConnected() || !client.isAuthenticated()) {
                // Only remove if the value is still the same stale client
                // (prevents evicting a freshly reconnected session)
                if (sessionCache.remove(host, client)) {
                    evictSession(host, client);
                    evicted++;
                }
            }
        }

        if (evicted > 0) {
            log.info("Health check: evicted {} dead sessions", evicted);
        }
    }

    /**
     * Evict a session with notification to the eviction listener.
     */
    private void evictSession(String canonicalHost, SSHClient staleClient) {
        log.warn("Evicting dead session for {}", canonicalHost);
        closeQuietly(staleClient);

        PortForwardEvictionListener listener = this.evictionListener;
        if (listener != null) {
            try {
                listener.onSessionEvicted(canonicalHost);
                log.warn("Evicted session for {} — notified port-forward listener", canonicalHost);
            } catch (Exception e) {
                log.error("Eviction listener threw exception for {}: {}", canonicalHost, e.getMessage());
            }
        } else {
            log.warn("Evicted session for {} — no port-forward listener registered", canonicalHost);
        }
    }

    private SSHClient buildClient(String targetHost) throws IOException {
        SSHClient client = new SSHClient(new DefaultConfig());
        loadKnownHosts(client);

        // Resolve config entry
        Map<String, String> config = sshConfig.getConfig(targetHost);

        String hostname = config.getOrDefault("HostName", targetHost);
        int port = Integer.parseInt(config.getOrDefault("Port", "22"));
        String user = config.getOrDefault("User", System.getProperty("user.name"));
        String proxyJump = config.get("ProxyJump");

        // Connect — either direct or via ProxyJump chain
        if (proxyJump == null || proxyJump.isEmpty() || "none".equalsIgnoreCase(proxyJump)) {
            client.connect(hostname, port);
        } else {
            resolveProxyJump(proxyJump, client, hostname, port);
        }

        // Enable keepalive — sends SSH_MSG_IGNORE every 30s to keep NAT mappings alive
        client.getConnection().getKeepAlive().setKeepAliveInterval(KEEPALIVE_INTERVAL_SECONDS);

        // Authenticate with identity file (auto-picks up -cert.pub if present)
        String identityFile = config.get("IdentityFile");
        if (identityFile != null) {
            String expandedPath = identityFile.replaceFirst("^~", System.getProperty("user.home"));
            KeyProvider keyProvider = client.loadKeys(expandedPath);
            client.authPublickey(user, keyProvider);
        } else {
            // Try default key locations
            client.authPublickey(user);
        }

        log.info("Connected to {} ({}:{}) as {} [keepalive={}s]",
                targetHost, hostname, port, user, KEEPALIVE_INTERVAL_SECONDS);
        return client;
    }

    private void resolveProxyJump(String proxyJump, SSHClient targetClient,
                                   String finalHost, int finalPort) throws IOException {
        String[] hops = proxyJump.split(",");

        // First hop: get or create a session to the first jump host
        SSHClient jumpClient = clientFor(hops[0].trim());

        // Intermediate hops (if chain depth > 2)
        for (int i = 1; i < hops.length; i++) {
            String hop = hops[i].trim();
            // Resolve the hop's config to get actual hostname and port
            Map<String, String> hopConfig = sshConfig.getConfig(hop);
            String hopHost = hopConfig.getOrDefault("HostName", hop);
            int hopPort = Integer.parseInt(hopConfig.getOrDefault("Port", "22"));

            // Create a direct connection through the current jump host
            SSHClient nextClient = new SSHClient(new DefaultConfig());
            loadKnownHosts(nextClient);
            DirectConnection dc = jumpClient.newDirectConnection(hopHost, hopPort);
            nextClient.connectVia(dc);

            // Authenticate the intermediate hop
            String hopUser = hopConfig.getOrDefault("User", System.getProperty("user.name"));
            String hopIdentity = hopConfig.get("IdentityFile");
            if (hopIdentity != null) {
                String expandedPath = hopIdentity.replaceFirst("^~", System.getProperty("user.home"));
                nextClient.authPublickey(hopUser, nextClient.loadKeys(expandedPath));
            } else {
                nextClient.authPublickey(hopUser);
            }

            jumpClient = nextClient;
        }

        // Final hop: connect the target client through the last jump host
        DirectConnection finalConnection = jumpClient.newDirectConnection(finalHost, finalPort);
        targetClient.connectVia(finalConnection);
    }

    /**
     * Loads SSH known hosts from ~/.ssh/known_hosts for host key verification.
     * If the file is missing or unreadable, logs a warning and throws IOException
     * to fail the connection rather than silently accepting unknown keys.
     */
    private void loadKnownHosts(SSHClient client) throws IOException {
        File knownHostsFile = new File(System.getProperty("user.home"), ".ssh/known_hosts");
        try {
            client.loadKnownHosts(knownHostsFile);
        } catch (IOException e) {
            log.warn("Cannot load SSH known_hosts from {}: {}. " +
                    "Connection will be refused — add the target host key to known_hosts first.",
                    knownHostsFile.getAbsolutePath(), e.getMessage());
            throw new IOException("SSH host key verification failed: unable to load known_hosts from " +
                    knownHostsFile.getAbsolutePath() + ". Ensure the file exists and the target host " +
                    "key is present. Details: " + e.getMessage(), e);
        }
    }

    private void closeQuietly(SSHClient client) {
        try {
            client.close();
        } catch (IOException e) {
            log.debug("Error closing SSH client: {}", e.getMessage());
        }
    }
}
