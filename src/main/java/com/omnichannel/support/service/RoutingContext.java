package com.omnichannel.support.service;

import lombok.Builder;

@Builder
public record RoutingContext(
        String issueType,
        String lob,
        String claimId,
        String policyId,
        String customerId) {}
