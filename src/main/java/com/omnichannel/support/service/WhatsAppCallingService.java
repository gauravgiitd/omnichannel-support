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
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WhatsAppCallingService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppCallingService.class);
    private static final String PERMISSION_REQUESTED = "REQUESTED";
    private static final String PERMISSION_GRANTED = "GRANTED";
    private static final String PERMISSION_REJECTED = "REJECTED";
    private static final String PERMISSION_REVOKED = "REVOKED";
    private static final String PERMISSION_SOURCE_META_WEBHOOK = "meta_webhook";

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
        if (hasText(sessionSdpType)) {
            call.setSessionSdpType(blankToNull(sessionSdpType));
        }
        if (hasText(sessionSdp)) {
            call.setSessionSdp(blankToNull(sessionSdp));
        }
        if (hasText(phoneNumberId)) {
            call.setPhoneNumberId(blankToNull(phoneNumberId));
        }
        if (hasText(displayPhoneNumber)) {
            call.setDisplayPhoneNumber(blankToNull(displayPhoneNumber));
        }
        if (startTime != null) {
            call.setStartTime(startTime);
        }
        if (endTime != null) {
            call.setEndTime(endTime);
        }
        if (durationSeconds != null) {
            call.setDurationSeconds(durationSeconds);
        }
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
        call.setPermissionStatus(PERMISSION_REQUESTED);
        call.setPermissionStatusUpdatedAt(Instant.now());
        call.setPermissionExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        call.setPermissionSource("agent_request");
        call.setExternalMessageId(externalMessageId);
        return whatsAppCallRepository.save(call);
    }

    @Transactional
    public WhatsAppCall recordPermissionStatus(String phoneNumber, String status, String source) {
        return recordPermissionStatus(phoneNumber, status, source, null);
    }

    @Transactional
    public WhatsAppCall recordPermissionStatus(String phoneNumber, String status, String source, Instant expiresAt) {
        String normalizedPhone = normalizePhone(phoneNumber);
        Optional<String> customerId = findCustomerId(normalizedPhone);
        WhatsAppCall call = latestPermissionRecord(customerId.orElse(null), normalizedPhone)
                .orElseGet(WhatsAppCall::new);
        if (call.getCallId() == null) {
            call.setCallId("permission-" + UUID.randomUUID());
        }
        call.setCustomerId(customerId.orElse(call.getCustomerId()));
        call.setPhoneNumber(normalizedPhone);
        call.setToPhone(normalizedPhone);
        call.setDirection("BUSINESS_INITIATED");
        call.setEvent("permission_status");
        call.setPermissionStatus(normalizePermissionStatus(status));
        call.setPermissionStatusUpdatedAt(Instant.now());
        call.setPermissionExpiresAt(expiresAt != null
                ? expiresAt
                : permissionExpiryForStatus(call.getPermissionStatus(), call.getPermissionStatusUpdatedAt()));
        call.setPermissionSource(blankToNull(source));
        return whatsAppCallRepository.save(call);
    }

    @Transactional(readOnly = true)
    public PermissionState currentPermissionState(String customerId) {
        return latestPermissionRecord(customerId, null)
                .map(this::toPermissionState)
                .orElse(PermissionState.none());
    }

    @Transactional
    public WhatsAppCallControlDto initiateOutgoingCall(
            String customerId,
            String phoneNumber,
            String sdpType,
            String sdp,
            String actorEmail) {
        PermissionState permissionState = currentPermissionState(customerId);
        if (!permissionState.granted()) {
            throw new ValidationException("WhatsApp call permission is not granted yet.");
        }
        if (!"offer".equalsIgnoreCase(blankToNull(sdpType)) || !hasText(sdp)) {
            throw new ValidationException("Outbound WhatsApp call requires a WebRTC SDP offer.");
        }
        String normalizedPhone = normalizePhone(phoneNumber);
        String callbackData = "customer:" + customerId + ":" + UUID.randomUUID();
        try {
            MetaWhatsAppCloudApiClient.CallInitiationResult result =
                    metaWhatsAppCloudApiClient.initiateCall(normalizedPhone, sdpType, sdp, callbackData);
            WhatsAppCall call = whatsAppCallRepository.findByCallId(result.callId()).orElseGet(WhatsAppCall::new);
            call.setCallId(result.callId());
            call.setCustomerId(customerId);
            call.setPhoneNumber(normalizedPhone);
            call.setToPhone(normalizedPhone);
            call.setDirection("BUSINESS_INITIATED");
            call.setEvent("connect_requested");
            call.setStatus("INITIATED");
            call.setSessionSdpType(blankToNull(sdpType));
            call.setSessionSdp(blankToNull(sdp));
            call.setPhoneNumberId(blankToNull(result.phoneNumberId()));
            call.setInitiatedBy(actorEmail);
            call.setInitiatedAt(Instant.now());
            call.setBizOpaqueCallbackData(callbackData);
            call.setPermissionStatus(permissionState.status());
            call.setPermissionStatusUpdatedAt(permissionState.updatedAt());
            call.setPermissionExpiresAt(permissionState.expiresAt());
            call.setPermissionSource("outbound_call");
            call.setRawPayloadJson(result.rawResponseBody());
            return toControlDto(whatsAppCallRepository.save(call));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ValidationException("WhatsApp outbound call was interrupted");
        } catch (IOException ex) {
            log.warn("WhatsApp outbound call initiation failed for customer {}: {}", customerId, ex.getMessage(), ex);
            throw new ValidationException("WhatsApp outbound call failed: " + ex.getMessage());
        }
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
                .filter(this::isCurrentCall)
                .limit(1)
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
        log.info(
                "Pre-accepting WhatsApp call {} for customer {} sdpType={} sdpLength={}",
                callId,
                customerId,
                sdpType,
                sdp == null ? 0 : sdp.length());
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
        log.info(
                "Accepting WhatsApp call {} for customer {} sdpType={} sdpLength={}",
                callId,
                customerId,
                sdpType,
                sdp == null ? 0 : sdp.length());
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
        log.info("Rejecting WhatsApp call {} for customer {}", callId, customerId);
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
        log.info("Terminating WhatsApp call {} for customer {}", callId, customerId);
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
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("WhatsApp call action interrupted for call {} action {}", call.getCallId(), action, ex);
            throw new ValidationException("WhatsApp call action was interrupted");
        } catch (IOException ex) {
            log.warn(
                    "WhatsApp call action failed for call {} action {} phoneNumberId {}: {}",
                    call.getCallId(),
                    action,
                    call.getPhoneNumberId(),
                    ex.getMessage(),
                    ex);
            throw new ValidationException("WhatsApp call action failed: " + ex.getMessage());
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
                call.getPermissionStatus(),
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
                call.getPermissionStatus(),
                call.getPermissionStatusUpdatedAt(),
                call.getPermissionExpiresAt(),
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

    private Optional<WhatsAppCall> latestPermissionRecord(String customerId, String phoneNumber) {
        return whatsAppCallRepository.findTop50ByOrderByUpdatedAtDesc().stream()
                .filter(call -> call.getPermissionStatus() != null || call.getPermissionRequestedAt() != null)
                .filter(call -> customerId == null || customerId.equals(call.getCustomerId()) || matchesPhone(call, phoneNumber))
                .filter(call -> phoneNumber == null || matchesPhone(call, phoneNumber))
                .max(java.util.Comparator.comparing(WhatsAppCall::getUpdatedAt));
    }

    private PermissionState toPermissionState(WhatsAppCall call) {
        String status = normalizePermissionStatus(call.getPermissionStatus());
        Instant updatedAt = call.getPermissionStatusUpdatedAt() != null
                ? call.getPermissionStatusUpdatedAt()
                : call.getPermissionRequestedAt();
        if (status == null && call.getPermissionRequestedAt() != null) {
            status = PERMISSION_REQUESTED;
        }
        Instant expiresAt = call.getPermissionExpiresAt() != null
                ? call.getPermissionExpiresAt()
                : permissionExpiryForStatus(status, updatedAt);
        if (expiresAt != null && expiresAt.isBefore(Instant.now()) && PERMISSION_GRANTED.equalsIgnoreCase(status)) {
            status = "EXPIRED";
        }
        boolean granted = PERMISSION_GRANTED.equalsIgnoreCase(status)
                && PERMISSION_SOURCE_META_WEBHOOK.equalsIgnoreCase(blankToNull(call.getPermissionSource()))
                && expiresAt != null
                && expiresAt.isAfter(Instant.now());
        return new PermissionState(status, updatedAt, expiresAt, granted);
    }

    private static Instant permissionExpiryForStatus(String status, Instant reference) {
        if (reference == null || status == null) {
            return null;
        }
        return switch (status.toUpperCase(java.util.Locale.ROOT)) {
            case PERMISSION_GRANTED, PERMISSION_REJECTED, PERMISSION_REQUESTED, PERMISSION_REVOKED -> reference.plus(7, ChronoUnit.DAYS);
            default -> reference;
        };
    }

    private static String normalizePermissionStatus(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private boolean matchesPhone(WhatsAppCall call, String phoneNumber) {
        String normalizedPhone = normalizePhone(phoneNumber);
        return normalizedPhone != null
                && (normalizedPhone.equals(normalizePhone(call.getPhoneNumber()))
                || normalizedPhone.equals(normalizePhone(call.getFromPhone()))
                || normalizedPhone.equals(normalizePhone(call.getToPhone())));
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isCurrentCall(WhatsAppCall call) {
        if (call == null) {
            return false;
        }
        if ("permission_status".equalsIgnoreCase(blankToNull(call.getEvent()))) {
            return false;
        }
        String status = blankToNull(call.getStatus());
        String event = blankToNull(call.getEvent());
        if ("PERMISSION_REQUESTED".equalsIgnoreCase(status) || "permission_requested".equalsIgnoreCase(event)) {
            return false;
        }
        return !("COMPLETED".equalsIgnoreCase(status)
                || "FAILED".equalsIgnoreCase(status)
                || "REJECTED".equalsIgnoreCase(status)
                || "TERMINATED".equalsIgnoreCase(status)
                || "terminate".equalsIgnoreCase(event));
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

    public record PermissionState(String status, Instant updatedAt, Instant expiresAt, boolean granted) {
        static PermissionState none() {
            return new PermissionState(null, null, null, false);
        }
    }
}
