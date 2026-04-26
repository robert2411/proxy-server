package com.github.robert2411.proxy;

import com.github.robert2411.ssh.PortForwardEvictionListener;
import com.github.robert2411.ssh.SshSessionManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder;
import net.schmizz.sshj.connection.channel.direct.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Cache for local port forwards. On first request to a (targetHost, targetPort) pair,
 * opens a LocalPortForwarder bound to an ephemeral loopback port. Subsequent requests
 * reuse the cached port. Implements PortForwardEvictionListener to invalidate forwards
 * when SSH sessions are evicted.
 */
@Component
public class PortForwardCache implements PortForwardEvictionListener {

    private static final Logger log = LoggerFactory.getLogger(PortForwardCache.class);

    private final SshSessionManager sshSessionManager;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ForwardEntry> cache = new ConcurrentHashMap<>();

    public PortForwardCache(SshSessionManager sshSessionManager, ExecutorService portForwardExecutor) {
        this.sshSessionManager = sshSessionManager;
        this.executor = portForwardExecutor;
    }

    @PostConstruct
    void init() {
        sshSessionManager.setEvictionListener(this);
        log.info("PortForwardCache registered as eviction listener on SshSessionManager");
    }

    /**
     * Returns the local loopback port that forwards to targetHost:targetPort via SSH.
     * Creates a new forwarder if none exists or if the existing one is dead.
     *
     * @param targetHost the SSH host alias to connect to
     * @param targetPort the port on the remote host to forward to
     * @return the local loopback port number
     * @throws IOException if SSH session or port forwarding setup fails
     */
    public int localPortFor(String targetHost, int targetPort) throws IOException {
        String key = cacheKey(targetHost, targetPort);
        try {
            ForwardEntry entry = cache.compute(key, (k, existing) -> {
                // Reuse existing if forwarder is still alive
                if (existing != null && !existing.future().isDone()) {
                    return existing;
                }
                // Dead forwarder — clean up before recreating
                if (existing != null) {
                    log.warn("Dead forwarder detected for {} — recreating", k);
                    closeEntry(existing);
                }
                // Create new forwarder
                try {
                    return createForwardEntry(targetHost, targetPort);
                } catch (IOException e) {
                    throw new PortForwardException(e);
                }
            });
            return entry.localPort();
        } catch (PortForwardException e) {
            throw (IOException) e.getCause();
        }
    }

    /**
     * Invalidates and closes the port forward for the given target.
     *
     * @param targetHost the target host
     * @param targetPort the target port
     */
    public void invalidate(String targetHost, int targetPort) {
        String key = cacheKey(targetHost, targetPort);
        ForwardEntry removed = cache.remove(key);
        if (removed != null) {
            closeEntry(removed);
            log.info("Invalidated port forward for {}", key);
        }
    }

    @Override
    public void onSessionEvicted(String canonicalHost) {
        log.info("SSH session evicted for {} — invalidating all associated port forwards", canonicalHost);
        // Iterate all entries and invalidate those matching the evicted host
        for (Map.Entry<String, ForwardEntry> entry : cache.entrySet()) {
            if (entry.getValue().targetHost().equals(canonicalHost)) {
                ForwardEntry removed = cache.remove(entry.getKey());
                if (removed != null) {
                    closeEntry(removed);
                    log.info("Invalidated port forward {} due to session eviction", entry.getKey());
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        log.info("Shutting down PortForwardCache — closing {} active forwards", cache.size());
        for (Map.Entry<String, ForwardEntry> entry : cache.entrySet()) {
            closeEntry(entry.getValue());
        }
        cache.clear();
    }

    /**
     * Returns the number of active cache entries (for testing/metrics).
     */
    public int size() {
        return cache.size();
    }

    // --- Private helpers ---

    private ForwardEntry createForwardEntry(String targetHost, int targetPort) throws IOException {
        SSHClient client = sshSessionManager.clientFor(targetHost);

        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        int localPort = serverSocket.getLocalPort();

        try {
            Parameters params = new Parameters("127.0.0.1", localPort, "localhost", targetPort);
            LocalPortForwarder forwarder = client.newLocalPortForwarder(params, serverSocket);

            Future<?> future = executor.submit(() -> {
                try {
                    forwarder.listen();
                } catch (IOException e) {
                    log.warn("Port forwarder for {}:{} exited: {}", targetHost, targetPort, e.getMessage());
                }
            });

            log.info("Created port forward 127.0.0.1:{} -> {}:{} (via SSH)", localPort, targetHost, targetPort);
            return new ForwardEntry(localPort, serverSocket, future, targetHost, targetPort);
        } catch (RejectedExecutionException e) {
            // Thread pool is saturated — fail fast with a clear error
            try {
                serverSocket.close();
            } catch (IOException closeEx) {
                log.debug("Error closing server socket during cleanup: {}", closeEx.getMessage());
            }
            throw new IOException("Port forwarder thread pool exhausted. " +
                    "Increase proxy.ssh.forwarder-threads or reduce active forwards.", e);
        } catch (Exception e) {
            // Close the ServerSocket if forwarder creation or submission fails
            try {
                serverSocket.close();
            } catch (IOException closeEx) {
                log.debug("Error closing server socket during cleanup: {}", closeEx.getMessage());
            }
            if (e instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("Failed to create port forward for " + targetHost + ":" + targetPort, e);
        }
    }

    private void closeEntry(ForwardEntry entry) {
        entry.future().cancel(true);
        try {
            entry.serverSocket().close();
        } catch (IOException e) {
            log.debug("Error closing server socket for port {}: {}", entry.localPort(), e.getMessage());
        }
    }

    private static String cacheKey(String host, int port) {
        return host + ":" + port;
    }

    // --- Internal types ---

    record ForwardEntry(int localPort, ServerSocket serverSocket, Future<?> future,
                        String targetHost, int targetPort) {
    }

    /**
     * Unchecked exception wrapper for IOException thrown inside compute lambda.
     */
    private static class PortForwardException extends RuntimeException {
        PortForwardException(IOException cause) {
            super(cause);
        }
    }
}
