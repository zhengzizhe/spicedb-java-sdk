package com.authx.clustertest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cluster")
public record ClusterProps(
        int nodeIndex,
        int nodeCount,
        int leaderPort,
        String resultsDir,
        SpiceDb spicedb,
        Toxiproxy toxiproxy
) {
    public boolean isLeader() { return nodeIndex == 0; }
    public record SpiceDb(String targets, String presharedKey) {}
    public record Toxiproxy(String host, int port, boolean enabled) {}
}
