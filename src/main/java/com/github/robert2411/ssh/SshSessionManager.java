package com.github.robert2411.ssh;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.connection.channel.direct.DirectConnection;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
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

/**
 * Core SSH lifecycle component. Reads ~/.ssh/config via custom parser,
 * resolves ProxyJump hops recursively, handles certificate auth, and maintains
 * a ConcurrentHashMap cache of live SSHClient sessions keyed by target host.
 */
@Component
@ConditionalOnProperty(name = "ssh.enabled", havingValue = "true", matchIfMissing = true)
public class SshSessionManager {

    private static final Logger log = LoggerFactory.getLogger(SshSessionManager.class);

    private final SshConfigParser sshConfig;
    private final ConcurrentHashMap<String, SSHClient> sessionCache = new ConcurrentHashMap<>();

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
    }

    /**
     * Package-private constructor for testing with pre-parsed config.
     */
    SshSessionManager(SshConfigParser config) {
        this.sshConfig = config;
        log.info("SshSessionManager initialized with provided config");
    }

    /**
     * Returns a connected and authenticated SSHClient for the given target host.
     * Uses the session cache for efficiency; stale sessions are evicted automatically.
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
                if (existing != null) {
                    log.warn("Evicting stale session for {}", key);
                    closeQuietly(existing);
                }
                try {
                    return buildClient(key);
                } catch (IOException e) {
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
     * Evicts a session from the cache (used by keepalive/reconnect logic).
     */
    public void evict(String targetHost) {
        SSHClient removed = sessionCache.remove(targetHost);
        if (removed != null) {
            log.info("Evicted session for {}", targetHost);
            closeQuietly(removed);
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

    private SSHClient buildClient(String targetHost) throws IOException {
        SSHClient client = new SSHClient(new DefaultConfig());
        client.addHostKeyVerifier(new PromiscuousVerifier()); // TODO: use KnownHosts in production

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

        log.info("Connected to {} ({}:{}) as {}", targetHost, hostname, port, user);
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
            nextClient.addHostKeyVerifier(new PromiscuousVerifier());
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

    private void closeQuietly(SSHClient client) {
        try {
            client.close();
        } catch (IOException e) {
            log.debug("Error closing SSH client: {}", e.getMessage());
        }
    }
}
