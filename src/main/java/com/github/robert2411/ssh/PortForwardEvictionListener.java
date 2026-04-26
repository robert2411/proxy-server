package com.github.robert2411.ssh;

/**
 * Listener interface for session eviction events.
 * Implemented by PortForwardCache (TASK-4) to invalidate port forwards
 * when their backing SSH session is evicted.
 */
@FunctionalInterface
public interface PortForwardEvictionListener {

    /**
     * Called when an SSH session is evicted from the cache.
     * Implementations should invalidate any port forwards associated with the given host.
     *
     * @param canonicalHost the canonical hostname of the evicted session
     */
    void onSessionEvicted(String canonicalHost);
}
