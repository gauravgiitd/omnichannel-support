package com.omnichannel.support.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support")
public record SupportPlatformProperties(
        OutboundEmail outboundEmail, SystemIdentity systemIdentity) {

    public record OutboundEmail(String fromAddress, String fromDisplayName) {}

    public record SystemIdentity(String agentReplyFromAddress) {}
}
