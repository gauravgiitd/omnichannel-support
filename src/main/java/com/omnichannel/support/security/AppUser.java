package com.omnichannel.support.security;

import java.util.Set;

public record AppUser(
        String email,
        String name,
        String pictureUrl,
        String customerId,
        Set<String> roles) {

    public boolean isAgent() {
        return roles.contains("ROLE_AGENT");
    }

    public boolean isExpert() {
        return roles.contains("ROLE_EXPERT");
    }
}
