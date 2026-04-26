package com.github.robert2411.ssh;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot HealthIndicator that reports the status of SSH sessions.
 * Reports UP when all cached sessions are connected and authenticated,
 * DOWN when any session is dead. Empty cache (startup) is considered UP.
 *
 * <p>Registers as "sshProxy" in /actuator/health (bean name minus "HealthIndicator" suffix).
 */
@Component("sshProxy")
public class SshHealthIndicator extends AbstractHealthIndicator {

    private final SshSessionManager sshSessionManager;

    public SshHealthIndicator(SshSessionManager sshSessionManager) {
        super("SSH session health check failed");
        this.sshSessionManager = sshSessionManager;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<String, Boolean> sessionStatus = sshSessionManager.sessionStatus();

        if (sessionStatus.isEmpty()) {
            builder.up().withDetail("sessions", "none cached");
            return;
        }

        Map<String, String> details = new LinkedHashMap<>();
        boolean allHealthy = true;

        for (Map.Entry<String, Boolean> entry : sessionStatus.entrySet()) {
            String host = entry.getKey();
            boolean healthy = entry.getValue();
            if (healthy) {
                details.put(host, "UP");
            } else {
                details.put(host, "DOWN (disconnected or unauthenticated)");
                allHealthy = false;
            }
        }

        if (allHealthy) {
            builder.up().withDetail("sessions", details);
        } else {
            builder.down().withDetail("sessions", details);
        }
    }
}
