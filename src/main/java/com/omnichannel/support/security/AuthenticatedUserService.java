package com.omnichannel.support.security;

import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.service.CustomerContactMappingService;
import com.omnichannel.support.service.IdentityResolutionService;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthenticatedUserService {

    private final IdentityResolutionService identityResolutionService;
    private final CustomerContactMappingService customerContactMappingService;

    public AppUser requireCurrentUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            throw new ValidationException("authenticated Google user is required");
        }

        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            throw new ValidationException("Google account email is required");
        }

        String customerId = resolveOrProvisionCustomer(email);

        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            roles.add(authority.getAuthority());
        }

        return new AppUser(email, oidcUser.getFullName(), oidcUser.getPicture(), customerId, roles);
    }

    private String provisionCustomer(String email) {
        String customerId = "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        identityResolutionService.registerLink(customerId, IdentifierType.EMAIL, email);
        return customerId;
    }

    private String resolveOrProvisionCustomer(String email) {
        Optional<String> direct = identityResolutionService.resolveCustomerId(IdentifierType.EMAIL, email);
        if (direct.isPresent()) {
            linkMappedPhoneIfPresent(direct.get(), email);
            return direct.get();
        }

        Optional<String> mappedPhone = customerContactMappingService.counterpartForEmail(email);
        if (mappedPhone.isPresent()) {
            Optional<String> mappedCustomer =
                    identityResolutionService.resolveCustomerId(IdentifierType.PHONE, mappedPhone.get());
            if (mappedCustomer.isPresent()) {
                identityResolutionService.registerLink(mappedCustomer.get(), IdentifierType.EMAIL, email);
                return mappedCustomer.get();
            }
        }

        return provisionCustomer(email);
    }

    private void linkMappedPhoneIfPresent(String customerId, String email) {
        customerContactMappingService.counterpartForEmail(email)
                .ifPresent(phone -> identityResolutionService.registerLink(customerId, IdentifierType.PHONE, phone));
    }

    public boolean isAgent(Authentication authentication) {
        return Optional.ofNullable(authentication)
                .stream()
                .flatMap(auth -> auth.getAuthorities().stream())
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_AGENT"::equals);
    }
}
