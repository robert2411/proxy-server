package com.github.robert2411.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SshConfigParser.
 */
class SshConfigParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parseValidConfig() throws IOException {
        File configFile = getTestConfigFile();
        SshConfigParser parser = SshConfigParser.parse(configFile);
        assertNotNull(parser);
    }

    @Test
    void parseThrowsFileNotFoundForMissingFile() {
        File missing = new File("/nonexistent/ssh/config");
        assertThrows(FileNotFoundException.class, () -> SshConfigParser.parse(missing));
    }

    @Test
    void getConfigReturnsBastionSettings() throws IOException {
        SshConfigParser parser = SshConfigParser.parse(getTestConfigFile());
        Map<String, String> config = parser.getConfig("bastion");

        assertEquals("bastion.example.com", config.get("HostName"));
        assertEquals("admin", config.get("User"));
        assertEquals("22", config.get("Port"));
        assertEquals("~/.ssh/id_ed25519", config.get("IdentityFile"));
    }

    @Test
    void getConfigReturnsAppServerWithProxyJump() throws IOException {
        SshConfigParser parser = SshConfigParser.parse(getTestConfigFile());
        Map<String, String> config = parser.getConfig("app-server");

        assertEquals("10.0.1.50", config.get("HostName"));
        assertEquals("deploy", config.get("User"));
        assertEquals("2222", config.get("Port"));
        assertEquals("bastion", config.get("ProxyJump"));
        assertEquals("~/.ssh/id_ed25519", config.get("IdentityFile"));
    }

    @Test
    void getConfigReturnsDeepServerWithMultiHopProxyJump() throws IOException {
        SshConfigParser parser = SshConfigParser.parse(getTestConfigFile());
        Map<String, String> config = parser.getConfig("deep-server");

        assertEquals("10.0.2.100", config.get("HostName"));
        assertEquals("root", config.get("User"));
        assertEquals("bastion,app-server", config.get("ProxyJump"));
    }

    @Test
    void getConfigReturnsEmptyMapForUnknownHost() throws IOException {
        SshConfigParser parser = SshConfigParser.parse(getTestConfigFile());
        Map<String, String> config = parser.getConfig("unknown-host");

        assertTrue(config.isEmpty());
    }

    @Test
    void getConfigReturnsDirectHostSettings() throws IOException {
        SshConfigParser parser = SshConfigParser.parse(getTestConfigFile());
        Map<String, String> config = parser.getConfig("direct-host");

        assertEquals("direct.example.com", config.get("HostName"));
        assertEquals("ubuntu", config.get("User"));
        assertNull(config.get("ProxyJump"));
    }

    @Test
    void parseHandlesWildcardHost() throws IOException {
        File configFile = tempDir.resolve("ssh_config").toFile();
        Files.writeString(configFile.toPath(), """
                Host myhost
                    HostName myhost.example.com
                    User special
                
                Host *
                    User defaultuser
                    Port 22
                """);

        SshConfigParser parser = SshConfigParser.parse(configFile);

        // myhost should get its own User but fall through to wildcard for Port
        Map<String, String> config = parser.getConfig("myhost");
        assertEquals("special", config.get("User"));
        assertEquals("22", config.get("Port"));
        assertEquals("myhost.example.com", config.get("HostName"));

        // unknown host should get wildcard settings
        Map<String, String> wildcardConfig = parser.getConfig("random-host");
        assertEquals("defaultuser", wildcardConfig.get("User"));
        assertEquals("22", wildcardConfig.get("Port"));
    }

    @Test
    void parseHandlesCommentsAndEmptyLines() throws IOException {
        File configFile = tempDir.resolve("ssh_config").toFile();
        Files.writeString(configFile.toPath(), """
                # This is a comment
                
                Host test
                    # Another comment
                    HostName test.example.com
                    
                    User testuser
                """);

        SshConfigParser parser = SshConfigParser.parse(configFile);
        Map<String, String> config = parser.getConfig("test");
        assertEquals("test.example.com", config.get("HostName"));
        assertEquals("testuser", config.get("User"));
    }

    @Test
    void parseHandlesEqualsDelimiter() throws IOException {
        File configFile = tempDir.resolve("ssh_config").toFile();
        Files.writeString(configFile.toPath(), """
                Host equalshost
                    HostName=equals.example.com
                    User=equalsuser
                """);

        SshConfigParser parser = SshConfigParser.parse(configFile);
        Map<String, String> config = parser.getConfig("equalshost");
        assertEquals("equals.example.com", config.get("HostName"));
        assertEquals("equalsuser", config.get("User"));
    }

    @Test
    void parseHandlesCaseInsensitiveKeywords() throws IOException {
        File configFile = tempDir.resolve("ssh_config").toFile();
        Files.writeString(configFile.toPath(), """
                Host casetest
                    hostname case.example.com
                    port 2222
                    user caseuser
                    identityfile ~/.ssh/id_rsa
                    proxyjump jumphost
                """);

        SshConfigParser parser = SshConfigParser.parse(configFile);
        Map<String, String> config = parser.getConfig("casetest");
        assertEquals("case.example.com", config.get("HostName"));
        assertEquals("2222", config.get("Port"));
        assertEquals("caseuser", config.get("User"));
        assertEquals("~/.ssh/id_rsa", config.get("IdentityFile"));
        assertEquals("jumphost", config.get("ProxyJump"));
    }

    private File getTestConfigFile() {
        return new File(getClass().getClassLoader().getResource("test_ssh_config").getFile());
    }
}
