package com.omnichannel.support.security;

import com.omnichannel.support.config.AuthProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAccessService {

    private final AuthProperties authProperties;

    public boolean isAllowedAdmin(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return authProperties.getAdmin().getAllowedEmails().stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }
}
