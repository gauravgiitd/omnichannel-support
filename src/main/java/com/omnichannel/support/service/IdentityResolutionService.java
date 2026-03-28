package com.omnichannel.support.service;

import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class IdentityResolutionService {

    private final CustomerIdentityLinkRepository linkRepository;

    public Optional<String> resolveCustomerId(IdentifierType type, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(type, rawValue);
        return linkRepository
                .findByIdentifierTypeAndIdentifierValue(type, normalized)
                .map(CustomerIdentityLink::getCustomerId);
    }

    @Transactional
    public void registerLink(String customerId, IdentifierType type, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new ValidationException("identifier_value is required");
        }
        String normalized = normalize(type, rawValue);
        Optional<CustomerIdentityLink> existing =
                linkRepository.findByIdentifierTypeAndIdentifierValue(type, normalized);
        if (existing.isPresent()) {
            if (!existing.get().getCustomerId().equals(customerId)) {
                throw new ValidationException("identifier already linked to another customer");
            }
            return;
        }
        CustomerIdentityLink link = new CustomerIdentityLink();
        link.setCustomerId(customerId);
        link.setIdentifierType(type);
        link.setIdentifierValue(normalized);
        linkRepository.save(link);
    }

    private static String normalize(IdentifierType type, String rawValue) {
        return switch (type) {
            case EMAIL -> rawValue.trim().toLowerCase(Locale.ROOT);
            case PHONE -> rawValue.replaceAll("\\s+", "").trim();
            case CUSTOMER_ID, POLICY_ID, CLAIM_ID -> rawValue.trim();
        };
    }
}
