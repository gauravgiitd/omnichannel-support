package com.omnichannel.support.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support")
public record SupportPlatformProperties(
        OutboundEmail outboundEmail, SystemIdentity systemIdentity, App app) {

    public record OutboundEmail(String fromAddress, String fromDisplayName) {}

    public record SystemIdentity(String agentReplyFromAddress) {}

    public record App(String baseUrl) {}
}
