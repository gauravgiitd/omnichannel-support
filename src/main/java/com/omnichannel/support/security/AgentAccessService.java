package com.omnichannel.support.security;

import com.omnichannel.support.config.AuthProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AgentAccessService {

    private final AuthProperties authProperties;
    private final GoogleGroupMembershipService googleGroupMembershipService;

    public boolean isAllowedAgent(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (authProperties.getAgent().getAllowedEmails().stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals)) {
            return true;
        }
        return googleGroupMembershipService.isMemberOfAnyConfiguredGroup(normalized);
    }
}
