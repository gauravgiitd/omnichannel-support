package com.omnichannel.support.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support.routing")
public record RoutingProperties(String triageQueue, List<RoutingRule> rules) {

    public RoutingProperties {
        if (triageQueue == null || triageQueue.isBlank()) {
            triageQueue = "queue-triage";
        }
        if (rules == null) {
            rules = List.of();
        }
    }

    public record RoutingRule(String issueType, String lob, String queue) {}
}
