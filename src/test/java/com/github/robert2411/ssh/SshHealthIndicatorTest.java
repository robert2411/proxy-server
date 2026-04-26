package com.github.robert2411.ssh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SshHealthIndicatorTest {

    private SshSessionManager sshSessionManager;
    private SshHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        sshSessionManager = mock(SshSessionManager.class);
        healthIndicator = new SshHealthIndicator(sshSessionManager);
    }

    @Test
    void health_upWhenAllSessionsConnected() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        status.put("host1", true);
        status.put("host2", true);
        when(sshSessionManager.sessionStatus()).thenReturn(status);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, String> sessions = (Map<String, String>) health.getDetails().get("sessions");
        assertThat(sessions).containsEntry("host1", "UP");
        assertThat(sessions).containsEntry("host2", "UP");
    }

    @Test
    void health_downWhenAnySessionDisconnected() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        status.put("host1", true);
        status.put("host2", false);
        when(sshSessionManager.sessionStatus()).thenReturn(status);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        Map<String, String> sessions = (Map<String, String>) health.getDetails().get("sessions");
        assertThat(sessions).containsEntry("host1", "UP");
        assertThat(sessions.get("host2")).contains("DOWN");
    }

    @Test
    void health_upWhenNoSessionsCached() {
        when(sshSessionManager.sessionStatus()).thenReturn(Map.of());

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("sessions")).isEqualTo("none cached");
    }

    @Test
    void health_downWhenAllSessionsDead() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        status.put("host1", false);
        status.put("host2", false);
        when(sshSessionManager.sessionStatus()).thenReturn(status);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        @SuppressWarnings("unchecked")
        Map<String, String> sessions = (Map<String, String>) health.getDetails().get("sessions");
        assertThat(sessions.get("host1")).contains("DOWN");
        assertThat(sessions.get("host2")).contains("DOWN");
    }

    @Test
    void health_detailsContainPerHostStatus() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        status.put("webserver", true);
        status.put("dbserver", false);
        status.put("cacheserver", true);
        when(sshSessionManager.sessionStatus()).thenReturn(status);

        Health health = healthIndicator.health();

        assertThat(health.getDetails()).containsKey("sessions");
        @SuppressWarnings("unchecked")
        Map<String, String> sessions = (Map<String, String>) health.getDetails().get("sessions");
        assertThat(sessions).hasSize(3);
        assertThat(sessions.get("webserver")).isEqualTo("UP");
        assertThat(sessions.get("dbserver")).contains("DOWN");
        assertThat(sessions.get("cacheserver")).isEqualTo("UP");
    }
}
