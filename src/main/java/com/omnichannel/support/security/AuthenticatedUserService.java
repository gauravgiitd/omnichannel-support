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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            roles.add(authority.getAuthority());
        }

        String customerId = shouldProvisionCustomerIdentity(roles)
                ? resolveOrProvisionCustomer(email)
                : resolveExistingCustomer(email).orElse(null);

        return new AppUser(email, oidcUser.getFullName(), oidcUser.getPicture(), customerId, roles);
    }

    private String provisionCustomer(String email) {
        String customerId = "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        identityResolutionService.registerLink(customerId, IdentifierType.EMAIL, email);
        return customerId;
    }

    private String resolveOrProvisionCustomer(String email) {
        Optional<String> existing = resolveExistingCustomer(email);
        if (existing.isPresent()) {
            return existing.get();
        }

        return provisionCustomer(email);
    }

    private Optional<String> resolveExistingCustomer(String email) {
        Optional<String> direct = identityResolutionService.resolveCustomerId(IdentifierType.EMAIL, email);
        if (direct.isPresent()) {
            linkMappedPhoneIfPresent(direct.get(), email);
            return direct;
        }

        Optional<String> mappedPhone = customerContactMappingService.counterpartForEmail(email);
        if (mappedPhone.isPresent()) {
            Optional<String> mappedCustomer =
                    identityResolutionService.resolveCustomerId(IdentifierType.PHONE, mappedPhone.get());
            if (mappedCustomer.isPresent()) {
                identityResolutionService.registerLink(mappedCustomer.get(), IdentifierType.EMAIL, email);
                return mappedCustomer;
            }
        }

        return Optional.empty();
    }

    private void linkMappedPhoneIfPresent(String customerId, String email) {
        customerContactMappingService.counterpartForEmail(email)
                .ifPresent(phone -> identityResolutionService.registerLink(customerId, IdentifierType.PHONE, phone));
    }

    public boolean isAgent(Authentication authentication) {
        return hasRole(authentication, "ROLE_AGENT");
    }

    public boolean isExpert(Authentication authentication) {
        return hasRole(authentication, "ROLE_EXPERT");
    }

    public boolean isAdmin(Authentication authentication) {
        return hasRole(authentication, "ROLE_ADMIN");
    }

    public boolean isStaff(Authentication authentication) {
        return isAgent(authentication) || isExpert(authentication) || isAdmin(authentication);
    }

    private boolean shouldProvisionCustomerIdentity(Set<String> roles) {
        if (!(roles.contains("ROLE_AGENT") || roles.contains("ROLE_EXPERT") || roles.contains("ROLE_ADMIN"))) {
            return true;
        }
        String path = currentRequestPath().orElse("");
        return path.startsWith("/v1/customers/me")
                || path.startsWith("/v1/tasks/me")
                || path.startsWith("/v1/inbound");
    }

    private Optional<String> currentRequestPath() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            return Optional.ofNullable(servletRequestAttributes.getRequest().getRequestURI());
        }
        return Optional.empty();
    }

    private boolean hasRole(Authentication authentication, String role) {
        return Optional.ofNullable(authentication)
                .stream()
                .flatMap(auth -> auth.getAuthorities().stream())
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role::equals);
    }
}
