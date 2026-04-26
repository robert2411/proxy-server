package com.github.robert2411.ssh;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for OpenSSH config files (~/.ssh/config).
 * Supports Host blocks with HostName, Port, User, IdentityFile, and ProxyJump directives.
 */
public class SshConfigParser {

    private final List<HostEntry> entries = new ArrayList<>();

    /**
     * Parse an SSH config file.
     *
     * @param configFile the SSH config file to parse
     * @return parsed SshConfigParser instance
     * @throws FileNotFoundException if the config file does not exist
     * @throws IOException if reading fails
     */
    public static SshConfigParser parse(File configFile) throws IOException {
        if (!configFile.exists()) {
            throw new FileNotFoundException("SSH config not found: " + configFile.getAbsolutePath());
        }
        SshConfigParser parser = new SshConfigParser();
        parser.parseFile(configFile);
        return parser;
    }

    /**
     * Get the resolved configuration for a given host alias.
     * Returns merged configuration from matching Host blocks (first match wins per directive).
     *
     * @param host the host alias to look up
     * @return a map of directive name to value
     */
    public Map<String, String> getConfig(String host) {
        Map<String, String> result = new LinkedHashMap<>();
        for (HostEntry entry : entries) {
            if (entry.matches(host)) {
                // First match wins for each directive
                for (Map.Entry<String, String> directive : entry.directives.entrySet()) {
                    result.putIfAbsent(directive.getKey(), directive.getValue());
                }
            }
        }
        return result;
    }

    private void parseFile(File configFile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            HostEntry currentEntry = null;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Split on first whitespace or '='
                String[] parts = line.split("[\\s=]+", 2);
                if (parts.length < 2) {
                    continue;
                }

                String keyword = parts[0];
                String value = parts[1].trim();

                if ("Host".equalsIgnoreCase(keyword)) {
                    currentEntry = new HostEntry(value);
                    entries.add(currentEntry);
                } else if (currentEntry != null) {
                    // Store directive with canonical case (first letter uppercase, rest as-is)
                    // SSH config keywords are case-insensitive, normalize to canonical form
                    String normalizedKeyword = normalizeKeyword(keyword);
                    currentEntry.directives.putIfAbsent(normalizedKeyword, value);
                }
            }
        }
    }

    /**
     * Normalize SSH config keywords to canonical camelCase form.
     * SSH config keywords are case-insensitive; we normalize to the canonical form
     * used in lookups (e.g., "hostname" -> "HostName", "proxyjump" -> "ProxyJump").
     */
    private static final Map<String, String> CANONICAL_KEYWORDS = Map.of(
            "hostname", "HostName",
            "port", "Port",
            "user", "User",
            "identityfile", "IdentityFile",
            "proxyjump", "ProxyJump",
            "proxycommand", "ProxyCommand",
            "identitiesonly", "IdentitiesOnly",
            "forwardagent", "ForwardAgent",
            "serveraliveinterval", "ServerAliveInterval",
            "serveralivecountmax", "ServerAliveCountMax"
    );

    private static String normalizeKeyword(String keyword) {
        String canonical = CANONICAL_KEYWORDS.get(keyword.toLowerCase());
        return canonical != null ? canonical : keyword;
    }

    /**
     * Represents a Host block in SSH config.
     */
    static class HostEntry {
        final String pattern;
        final Map<String, String> directives = new LinkedHashMap<>();

        HostEntry(String pattern) {
            this.pattern = pattern;
        }

        boolean matches(String host) {
            // Support simple wildcard patterns
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.contains("*")) {
                String regex = pattern.replace(".", "\\.").replace("*", ".*").replace("?", ".");
                return host.matches(regex);
            }
            // Support multiple patterns separated by space
            for (String p : pattern.split("\\s+")) {
                if (p.equalsIgnoreCase(host)) {
                    return true;
                }
            }
            return false;
        }
    }
}
