package com.omnichannel.support.service;

import com.omnichannel.support.domain.CustomerContactMapping;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.dto.CustomerContactMappingDto;
import com.omnichannel.support.dto.UpsertCustomerContactMappingRequest;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.CustomerContactMappingRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerContactMappingService {

    private final CustomerContactMappingRepository customerContactMappingRepository;
    private final IdentityResolutionService identityResolutionService;

    @Transactional(readOnly = true)
    public List<CustomerContactMappingDto> listAll() {
        return customerContactMappingRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(CustomerContactMapping::getUpdatedAt).reversed())
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public CustomerContactMappingDto create(UpsertCustomerContactMappingRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        String normalizedPhone = normalizePhone(request.phone());
        ensureUnique(normalizedEmail, normalizedPhone, null);
        alignExistingIdentityLinks(normalizedEmail, normalizedPhone);

        CustomerContactMapping mapping = new CustomerContactMapping();
        mapping.setEmail(normalizedEmail);
        mapping.setPhone(normalizedPhone);
        return toDto(customerContactMappingRepository.save(mapping));
    }

    @Transactional
    public CustomerContactMappingDto update(Long id, UpsertCustomerContactMappingRequest request) {
        CustomerContactMapping mapping = customerContactMappingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("contact mapping not found"));
        String normalizedEmail = normalizeEmail(request.email());
        String normalizedPhone = normalizePhone(request.phone());
        ensureUnique(normalizedEmail, normalizedPhone, id);
        alignExistingIdentityLinks(normalizedEmail, normalizedPhone);

        mapping.setEmail(normalizedEmail);
        mapping.setPhone(normalizedPhone);
        return toDto(customerContactMappingRepository.save(mapping));
    }

    @Transactional
    public void delete(Long id) {
        CustomerContactMapping mapping = customerContactMappingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("contact mapping not found"));
        customerContactMappingRepository.delete(mapping);
    }

    @Transactional(readOnly = true)
    public Optional<String> counterpartForEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return customerContactMappingRepository.findByEmail(normalizeEmail(email)).map(CustomerContactMapping::getPhone);
    }

    @Transactional(readOnly = true)
    public Optional<String> counterpartForPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        return customerContactMappingRepository.findByPhone(normalizePhone(phone)).map(CustomerContactMapping::getEmail);
    }

    private void ensureUnique(String email, String phone, Long currentId) {
        customerContactMappingRepository.findByEmail(email).ifPresent(existing -> {
            if (currentId == null || !existing.getId().equals(currentId)) {
                throw new ValidationException("email is already mapped to another phone");
            }
        });
        customerContactMappingRepository.findByPhone(phone).ifPresent(existing -> {
            if (currentId == null || !existing.getId().equals(currentId)) {
                throw new ValidationException("phone is already mapped to another email");
            }
        });
    }

    private void alignExistingIdentityLinks(String email, String phone) {
        Optional<String> emailCustomer = identityResolutionService.resolveCustomerId(IdentifierType.EMAIL, email);
        Optional<String> phoneCustomer = identityResolutionService.resolveCustomerId(IdentifierType.PHONE, phone);
        if (emailCustomer.isPresent() && phoneCustomer.isPresent() && !emailCustomer.get().equals(phoneCustomer.get())) {
            throw new ValidationException("email and phone are already linked to different customers");
        }
    }

    private CustomerContactMappingDto toDto(CustomerContactMapping mapping) {
        return new CustomerContactMappingDto(
                mapping.getId(), mapping.getEmail(), mapping.getPhone(), mapping.getCreatedAt(), mapping.getUpdatedAt());
    }

    private static String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizePhone(String value) {
        String normalized = value.replaceAll("\\s+", "").trim();
        return normalized.startsWith("+") ? normalized : "+" + normalized.replaceAll("[^0-9]", "");
    }
}
