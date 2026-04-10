package com.omnichannel.support.service;

import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.WhatsAppCall;
import com.omnichannel.support.dto.WhatsAppCallControlDto;
import com.omnichannel.support.dto.WhatsAppCallEventDto;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.error.ValidationException;
import java.io.IOException;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.WhatsAppCallRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WhatsAppCallingService {

    private final WhatsAppCallRepository whatsAppCallRepository;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final MetaWhatsAppCloudApiClient metaWhatsAppCloudApiClient;

    @Transactional
    public WhatsAppCall recordWebhookEvent(
            String callId,
            String fromPhone,
            String toPhone,
            String status,
            String direction,
            String event,
            String sessionSdpType,
            String sessionSdp,
            String phoneNumberId,
            String displayPhoneNumber,
            Instant startTime,
            Instant endTime,
            Integer durationSeconds,
            String rawPayloadJson) {
        WhatsAppCall call = whatsAppCallRepository.findByCallId(callId).orElseGet(WhatsAppCall::new);
        call.setCallId(callId);
        call.setFromPhone(normalizePhone(fromPhone));
        call.setToPhone(normalizePhone(toPhone));
        call.setPhoneNumber(resolveCustomerPhone(call.getFromPhone(), call.getToPhone()));
        call.setCustomerId(resolveCustomerId(call.getFromPhone(), call.getToPhone()).orElse(call.getCustomerId()));
        call.setStatus(blankToNull(status));
        call.setDirection(blankToNull(direction));
        call.setEvent(blankToNull(event));
        call.setSessionSdpType(blankToNull(sessionSdpType));
        call.setSessionSdp(blankToNull(sessionSdp));
        call.setPhoneNumberId(blankToNull(phoneNumberId));
        call.setDisplayPhoneNumber(blankToNull(displayPhoneNumber));
        call.setStartTime(startTime);
        call.setEndTime(endTime);
        call.setDurationSeconds(durationSeconds);
        call.setRawPayloadJson(rawPayloadJson);
        return whatsAppCallRepository.save(call);
    }

    @Transactional
    public WhatsAppCall recordPermissionRequest(
            String customerId,
            String phoneNumber,
            String agentEmail,
            String externalMessageId) {
        WhatsAppCall call = new WhatsAppCall();
        call.setCallId("permission-" + UUID.randomUUID());
        call.setCustomerId(customerId);
        call.setPhoneNumber(normalizePhone(phoneNumber));
        call.setToPhone(normalizePhone(phoneNumber));
        call.setStatus("PERMISSION_REQUESTED");
        call.setDirection("BUSINESS_INITIATED");
        call.setEvent("permission_requested");
        call.setPermissionRequestedBy(agentEmail);
        call.setPermissionRequestedAt(Instant.now());
        call.setExternalMessageId(externalMessageId);
        return whatsAppCallRepository.save(call);
    }

    @Transactional(readOnly = true)
    public List<WhatsAppCallEventDto> listRecentForCustomer(String customerId) {
        Set<String> customerPhones = customerPhones(customerId);
        LinkedHashMap<String, WhatsAppCall> callsById = new LinkedHashMap<>();
        whatsAppCallRepository.findTop10ByCustomerIdOrderByUpdatedAtDesc(customerId).forEach(call -> callsById.put(call.getCallId(), call));
        whatsAppCallRepository.findTop50ByOrderByUpdatedAtDesc().stream()
                .filter(call -> matchesCustomerPhones(call, customerPhones))
                .forEach(call -> callsById.putIfAbsent(call.getCallId(), call));
        return callsById.values().stream()
                .sorted((left, right) -> right.getUpdatedAt().compareTo(left.getUpdatedAt()))
                .limit(10)
                .map(this::toEventDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public WhatsAppCallControlDto getCallControl(String customerId, String callId) {
        WhatsAppCall call = requireOnCustomer(customerId, callId);
        return toControlDto(call);
    }

    @Transactional
    public WhatsAppCallControlDto preAcceptCall(String customerId, String callId, String sdpType, String sdp, String actorEmail) {
        WhatsAppCall call = requireOnCustomer(customerId, callId);
        performCallAction(call, "pre_accept", sdpType, sdp);
        call.setStatus("PRE_ACCEPTED");
        call.setEvent("pre_accept");
        call.setSessionSdpType(blankToNull(sdpType));
        call.setSessionSdp(blankToNull(sdp));
        call.setPermissionRequestedBy(actorEmail);
        return toControlDto(whatsAppCallRepository.save(call));
    }

    @Transactional
    public WhatsAppCallControlDto acceptCall(String customerId, String callId, String sdpType, String sdp, String actorEmail) {
        WhatsAppCall call = requireOnCustomer(customerId, callId);
        performCallAction(call, "accept", sdpType, sdp);
        call.setStatus("ACCEPTED");
        call.setEvent("accept");
        call.setSessionSdpType(blankToNull(sdpType));
        call.setSessionSdp(blankToNull(sdp));
        call.setPermissionRequestedBy(actorEmail);
        call.setStartTime(call.getStartTime() == null ? Instant.now() : call.getStartTime());
        return toControlDto(whatsAppCallRepository.save(call));
    }

    @Transactional
    public WhatsAppCallControlDto rejectCall(String customerId, String callId, String actorEmail) {
        WhatsAppCall call = requireOnCustomer(customerId, callId);
        performCallAction(call, "reject", null, null);
        call.setStatus("REJECTED");
        call.setEvent("reject");
        call.setPermissionRequestedBy(actorEmail);
        call.setEndTime(Instant.now());
        return toControlDto(whatsAppCallRepository.save(call));
    }

    @Transactional
    public WhatsAppCallControlDto terminateCall(String customerId, String callId, String actorEmail) {
        WhatsAppCall call = requireOnCustomer(customerId, callId);
        performCallAction(call, "terminate", null, null);
        call.setStatus("TERMINATED");
        call.setEvent("terminate");
        call.setPermissionRequestedBy(actorEmail);
        call.setEndTime(Instant.now());
        return toControlDto(whatsAppCallRepository.save(call));
    }

    private void performCallAction(WhatsAppCall call, String action, String sdpType, String sdp) {
        if (!metaWhatsAppCloudApiClient.canManageCalls()) {
            throw new ValidationException("WhatsApp calling is not configured");
        }
        try {
            metaWhatsAppCloudApiClient.performCallAction(
                    call.getPhoneNumberId(),
                    call.getCallId(),
                    action,
                    sdpType,
                    sdp);
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ValidationException("failed to perform WhatsApp call action");
        }
    }

    private WhatsAppCall requireOnCustomer(String customerId, String callId) {
        WhatsAppCall call = whatsAppCallRepository.findByCallId(callId)
                .orElseThrow(() -> new NotFoundException("whatsapp call not found"));
        if (call.getCustomerId() != null && call.getCustomerId().equals(customerId)) {
            return call;
        }
        if (matchesCustomerPhones(call, customerPhones(customerId))) {
            return call;
        }
        throw new NotFoundException("whatsapp call not found on selected customer");
    }

    private WhatsAppCallEventDto toEventDto(WhatsAppCall call) {
        return new WhatsAppCallEventDto(
                call.getCallId(),
                call.getFromPhone(),
                call.getToPhone(),
                call.getStatus(),
                call.getDirection(),
                call.getEvent(),
                call.getSessionSdpType(),
                call.getSessionSdp() != null && !call.getSessionSdp().isBlank(),
                call.getUpdatedAt());
    }

    private WhatsAppCallControlDto toControlDto(WhatsAppCall call) {
        return new WhatsAppCallControlDto(
                call.getCallId(),
                call.getCustomerId(),
                call.getPhoneNumber(),
                call.getFromPhone(),
                call.getToPhone(),
                call.getStatus(),
                call.getDirection(),
                call.getEvent(),
                call.getSessionSdpType(),
                call.getSessionSdp(),
                call.getPhoneNumberId(),
                call.getDisplayPhoneNumber(),
                call.getSessionSdp() != null && !call.getSessionSdp().isBlank()
                        && !"COMPLETED".equalsIgnoreCase(call.getStatus())
                        && !"FAILED".equalsIgnoreCase(call.getStatus())
                        && !"REJECTED".equalsIgnoreCase(call.getStatus())
                        && !"TERMINATED".equalsIgnoreCase(call.getStatus()),
                !"COMPLETED".equalsIgnoreCase(call.getStatus())
                        && !"FAILED".equalsIgnoreCase(call.getStatus())
                        && !"REJECTED".equalsIgnoreCase(call.getStatus())
                        && !"TERMINATED".equalsIgnoreCase(call.getStatus()),
                call.getUpdatedAt());
    }

    private Optional<String> resolveCustomerId(String fromPhone, String toPhone) {
        return findCustomerId(fromPhone).or(() -> findCustomerId(toPhone));
    }

    private Optional<String> findCustomerId(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        String normalizedPhone = normalizePhone(phone);
        return customerIdentityLinkRepository.findAll().stream()
                .filter(link -> link.getIdentifierType() == IdentifierType.PHONE)
                .filter(link -> normalizedPhone.equals(normalizePhone(link.getIdentifierValue())))
                .map(CustomerIdentityLink::getCustomerId)
                .findFirst();
    }

    private String resolveCustomerPhone(String fromPhone, String toPhone) {
        if (findCustomerId(fromPhone).isPresent()) {
            return fromPhone;
        }
        if (findCustomerId(toPhone).isPresent()) {
            return toPhone;
        }
        return fromPhone != null && !fromPhone.isBlank() ? fromPhone : toPhone;
    }

    private static String normalizePhone(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^\\d+]", "").trim();
        if (digits.isBlank()) {
            return null;
        }
        return digits.startsWith("+") ? digits : "+" + digits;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Set<String> customerPhones(String customerId) {
        return customerIdentityLinkRepository.findByCustomerId(customerId).stream()
                .filter(link -> link.getIdentifierType() == IdentifierType.PHONE)
                .map(CustomerIdentityLink::getIdentifierValue)
                .map(WhatsAppCallingService::normalizePhone)
                .filter(phone -> phone != null && !phone.isBlank())
                .collect(java.util.stream.Collectors.toSet());
    }

    private boolean matchesCustomerPhones(WhatsAppCall call, Set<String> customerPhones) {
        if (customerPhones == null || customerPhones.isEmpty()) {
            return false;
        }
        return customerPhones.contains(normalizePhone(call.getPhoneNumber()))
                || customerPhones.contains(normalizePhone(call.getFromPhone()))
                || customerPhones.contains(normalizePhone(call.getToPhone()));
    }
}
