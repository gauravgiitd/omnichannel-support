package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.JtbdInstanceStatus;
import com.omnichannel.support.domain.JtbdType;
import com.omnichannel.support.domain.JtbdTypeStage;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketStatus;
import com.omnichannel.support.dto.CreateCustomerJtbdRequest;
import com.omnichannel.support.dto.CustomerJtbdDto;
import com.omnichannel.support.dto.CustomerSummaryDto;
import com.omnichannel.support.dto.JtbdStageDto;
import com.omnichannel.support.dto.JtbdTypeDto;
import com.omnichannel.support.dto.UpdateCustomerJtbdRequest;
import com.omnichannel.support.dto.UpsertJtbdTypeRequest;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.CustomerIdentityLinkRepository;
import com.omnichannel.support.repo.CustomerJtbdRepository;
import com.omnichannel.support.repo.JtbdTypeRepository;
import com.omnichannel.support.repo.JtbdTypeStageRepository;
import com.omnichannel.support.repo.TicketRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class JtbdService {

    private static final Set<TicketStatus> OPEN_LIKE =
            EnumSet.of(
                    TicketStatus.OPEN,
                    TicketStatus.ASSIGNED,
                    TicketStatus.PENDING_CUSTOMER,
                    TicketStatus.PENDING_INTERNAL,
                    TicketStatus.REOPENED);

    private final JtbdTypeRepository jtbdTypeRepository;
    private final JtbdTypeStageRepository jtbdTypeStageRepository;
    private final CustomerJtbdRepository customerJtbdRepository;
    private final CustomerIdentityLinkRepository customerIdentityLinkRepository;
    private final TicketRepository ticketRepository;
    private final CustomerChannelNotificationService customerChannelNotificationService;
    private final ConversationService conversationService;
    private final CustomerConversationContextService customerConversationContextService;

    @Transactional(readOnly = true)
    public List<JtbdTypeDto> listTypes() {
        return jtbdTypeRepository.findAll().stream()
                .sorted(Comparator.comparing(JtbdType::getUpdatedAt).reversed())
                .map(this::toTypeDto)
                .toList();
    }

    @Transactional
    public JtbdTypeDto createType(UpsertJtbdTypeRequest request) {
        validateStages(request.stages());
        JtbdType type = new JtbdType();
        type.setPublicId(UUID.randomUUID().toString());
        type.setName(request.name().trim());
        type.setDescription(blankToNull(request.description()));
        jtbdTypeRepository.save(type);
        saveStages(type, request.stages());
        return toTypeDto(type);
    }

    @Transactional
    public JtbdTypeDto updateType(String publicId, UpsertJtbdTypeRequest request) {
        validateStages(request.stages());
        JtbdType type = loadType(publicId);
        type.setName(request.name().trim());
        type.setDescription(blankToNull(request.description()));
        jtbdTypeRepository.save(type);
        jtbdTypeStageRepository.deleteAll(jtbdTypeStageRepository.findByJtbdTypeOrderByStageOrderAsc(type));
        saveStages(type, request.stages());
        return toTypeDto(type);
    }

    @Transactional
    public void deleteType(String publicId) {
        JtbdType type = loadType(publicId);
        boolean hasInstances = customerJtbdRepository.findAll().stream()
                .anyMatch(instance -> instance.getJtbdType().getId().equals(type.getId()));
        if (hasInstances) {
            throw new ValidationException("cannot delete a JTBD type that already has customer instances");
        }
        jtbdTypeRepository.delete(type);
    }

    @Transactional(readOnly = true)
    public List<CustomerSummaryDto> listCustomers() {
        Map<String, CustomerAccumulator> customers = new LinkedHashMap<>();

        customerIdentityLinkRepository.findAll().forEach(link -> {
            CustomerAccumulator acc = customers.computeIfAbsent(link.getCustomerId(), CustomerAccumulator::new);
            if (link.getIdentifierType() == IdentifierType.EMAIL) {
                acc.emails.add(link.getIdentifierValue());
            }
            if (link.getIdentifierType() == IdentifierType.PHONE) {
                acc.phones.add(link.getIdentifierValue());
            }
        });

        ticketRepository.findAll().forEach(ticket -> {
            CustomerAccumulator acc = customers.computeIfAbsent(ticket.getCustomerId(), CustomerAccumulator::new);
            acc.ticketCount += 1;
        });

        customerJtbdRepository.findAll().forEach(instance -> {
            CustomerAccumulator acc = customers.computeIfAbsent(instance.getCustomerId(), CustomerAccumulator::new);
            if (instance.getStatus() == JtbdInstanceStatus.ACTIVE) {
                acc.activeJtbdCount += 1;
            }
        });

        return customers.values().stream()
                .sorted(Comparator.comparing(CustomerAccumulator::customerId))
                .map(CustomerAccumulator::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerJtbdDto> listCustomerJtbds(String customerId) {
        return customerJtbdRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toCustomerJtbdDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerJtbd> activeJtbdsForCustomer(String customerId) {
        return customerJtbdRepository.findByCustomerIdAndStatusOrderByCreatedAtDesc(customerId, JtbdInstanceStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<CustomerJtbd> findSingleActiveJtbdForCustomer(String customerId) {
        List<CustomerJtbd> active = activeJtbdsForCustomer(customerId);
        if (active.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(active.get(0));
    }

    @Transactional
    public CustomerJtbdDto createCustomerJtbd(String customerId, CreateCustomerJtbdRequest request) {
        JtbdType type = loadType(request.jtbdTypeId());
        List<JtbdTypeStage> stages = jtbdTypeStageRepository.findByJtbdTypeOrderByStageOrderAsc(type);
        if (stages.isEmpty()) {
            throw new ValidationException("JTBD type must have at least one stage");
        }
        JtbdTypeStage initialStage = resolveStage(type, request.initialStageKey()).orElse(stages.get(0));
        CustomerJtbd instance = new CustomerJtbd();
        instance.setPublicId(UUID.randomUUID().toString());
        instance.setCustomerId(customerId.trim());
        instance.setJtbdType(type);
        instance.setCurrentStage(initialStage);
        instance.setStatus(initialStage.isTerminalCompleted() ? JtbdInstanceStatus.COMPLETED : JtbdInstanceStatus.ACTIVE);
        customerJtbdRepository.save(instance);
        return toCustomerJtbdDto(instance);
    }

    @Transactional
    public CustomerJtbdDto updateCustomerJtbd(String publicId, UpdateCustomerJtbdRequest request) {
        CustomerJtbd instance = loadCustomerJtbd(publicId);
        JtbdTypeStage stage = resolveStage(instance.getJtbdType(), request.stageKey())
                .orElseThrow(() -> new ValidationException("JTBD stage not found for selected type"));
        boolean completingNow = stage.isTerminalCompleted() && instance.getStatus() != JtbdInstanceStatus.COMPLETED;
        instance.setCurrentStage(stage);
        instance.setStatus(stage.isTerminalCompleted() ? JtbdInstanceStatus.COMPLETED : JtbdInstanceStatus.ACTIVE);
        customerJtbdRepository.save(instance);
        if (completingNow) {
            closeTicketsForCompletedJtbd(instance);
        }
        return toCustomerJtbdDto(instance);
    }

    @Transactional
    public void deleteCustomerJtbd(String publicId) {
        CustomerJtbd instance = loadCustomerJtbd(publicId);
        boolean hasTickets = ticketRepository.findAll().stream()
                .anyMatch(ticket -> ticket.getCustomerJtbd() != null
                        && ticket.getCustomerJtbd().getId().equals(instance.getId()));
        if (hasTickets) {
            throw new ValidationException("cannot delete a customer JTBD that already has linked tickets");
        }
        customerJtbdRepository.delete(instance);
    }

    @Transactional(readOnly = true)
    public CustomerJtbd loadCustomerJtbd(String publicId) {
        return customerJtbdRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("customer JTBD not found"));
    }

    private void closeTicketsForCompletedJtbd(CustomerJtbd instance) {
        List<Ticket> tickets = ticketRepository.findByCustomerJtbdIdAndStatusInOrderByCreatedAtDesc(instance.getId(), OPEN_LIKE);
        for (Ticket ticket : tickets) {
            ticket.setStatus(TicketStatus.CLOSED);
            ticketRepository.save(ticket);
            customerConversationContextService.clearTicketReferences(ticket.getTicketNumber());
            notifyCompletion(ticket, instance);
        }
    }

    private void notifyCompletion(Ticket ticket, CustomerJtbd instance) {
        String body = "The job \"" + instance.getJtbdType().getName() + "\" is complete. Ticket "
                + ticket.getTicketNumber() + " is now closed.";
        String recipient = switch (ticket.getSourceChannel()) {
            case EMAIL -> latestIdentifier(ticket.getCustomerId(), IdentifierType.EMAIL);
            case WHATSAPP -> latestIdentifier(ticket.getCustomerId(), IdentifierType.PHONE);
            case UI -> null;
        };

        String externalThreadRef = null;
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("jtbd_public_id", instance.getPublicId());
        metadata.put("jtbd_stage", instance.getCurrentStage().getStageKey());

        if (ticket.getSourceChannel() != ChannelType.UI && recipient != null) {
            try {
                String subject = "[" + ticket.getTicketNumber() + "] JTBD completed";
                CustomerChannelNotificationService.DirectDeliveryResult delivery =
                        customerChannelNotificationService.send(ticket.getSourceChannel(), recipient, subject, body);
                externalThreadRef = delivery.externalThreadRef();
                metadata.putAll(delivery.metadata());
                metadata.put("delivery_status", "sent");
            } catch (ValidationException ex) {
                metadata.put("delivery_status", "failed");
                metadata.put("delivery_error", ex.getMessage());
            }
        } else {
            metadata.put("delivery_status", "app_only");
        }

        conversationService.appendMessage(
                ticket,
                ticket.getSourceChannel(),
                SenderType.SYSTEM,
                "jtbd-system",
                body,
                List.of(),
                externalThreadRef,
                metadata);
    }

    private String latestIdentifier(String customerId, IdentifierType type) {
        return customerIdentityLinkRepository.findByCustomerId(customerId).stream()
                .filter(link -> link.getIdentifierType() == type)
                .sorted(Comparator.comparing(CustomerIdentityLink::getCreatedAt).reversed())
                .map(CustomerIdentityLink::getIdentifierValue)
                .findFirst()
                .orElse(null);
    }

    private void validateStages(List<UpsertJtbdTypeRequest.StageRequest> stages) {
        if (stages == null || stages.isEmpty()) {
            throw new ValidationException("JTBD type must have at least one stage");
        }
        long terminalCount = stages.stream().filter(UpsertJtbdTypeRequest.StageRequest::terminalCompleted).count();
        if (terminalCount != 1) {
            throw new ValidationException("JTBD type must have exactly one terminal completed stage");
        }
        int maxOrder = stages.stream().mapToInt(UpsertJtbdTypeRequest.StageRequest::stageOrder).max().orElse(0);
        boolean terminalIsLast = stages.stream()
                .filter(UpsertJtbdTypeRequest.StageRequest::terminalCompleted)
                .allMatch(stage -> stage.stageOrder() == maxOrder);
        if (!terminalIsLast) {
            throw new ValidationException("terminal completed stage must be the last stage");
        }
    }

    private void saveStages(JtbdType type, List<UpsertJtbdTypeRequest.StageRequest> stages) {
        stages.stream()
                .sorted(Comparator.comparing(UpsertJtbdTypeRequest.StageRequest::stageOrder))
                .forEach(stageRequest -> {
                    JtbdTypeStage stage = new JtbdTypeStage();
                    stage.setJtbdType(type);
                    stage.setStageKey(normalizeStageKey(stageRequest.stageKey(), stageRequest.stageName()));
                    stage.setStageName(stageRequest.stageName().trim());
                    stage.setStageOrder(stageRequest.stageOrder());
                    stage.setTerminalCompleted(stageRequest.terminalCompleted());
                    jtbdTypeStageRepository.save(stage);
                });
    }

    private Optional<JtbdTypeStage> resolveStage(JtbdType type, String stageKey) {
        if (stageKey == null || stageKey.isBlank()) {
            return Optional.empty();
        }
        return jtbdTypeStageRepository.findByJtbdTypeAndStageKey(type, normalizeStageKey(stageKey, stageKey));
    }

    private JtbdType loadType(String publicId) {
        return jtbdTypeRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("JTBD type not found"));
    }

    private JtbdTypeDto toTypeDto(JtbdType type) {
        return new JtbdTypeDto(
                type.getPublicId(),
                type.getName(),
                type.getDescription(),
                jtbdTypeStageRepository.findByJtbdTypeOrderByStageOrderAsc(type).stream()
                        .map(stage -> new JtbdStageDto(
                                stage.getStageKey(),
                                stage.getStageName(),
                                stage.getStageOrder(),
                                stage.isTerminalCompleted()))
                        .toList(),
                type.getCreatedAt(),
                type.getUpdatedAt());
    }

    private CustomerJtbdDto toCustomerJtbdDto(CustomerJtbd instance) {
        return new CustomerJtbdDto(
                instance.getPublicId(),
                instance.getCustomerId(),
                instance.getJtbdType().getPublicId(),
                instance.getJtbdType().getName(),
                instance.getCurrentStage().getStageKey(),
                instance.getCurrentStage().getStageName(),
                instance.getStatus(),
                jtbdTypeStageRepository.findByJtbdTypeOrderByStageOrderAsc(instance.getJtbdType()).stream()
                        .map(stage -> new JtbdStageDto(
                                stage.getStageKey(),
                                stage.getStageName(),
                                stage.getStageOrder(),
                                stage.isTerminalCompleted()))
                        .toList(),
                instance.getCreatedAt(),
                instance.getUpdatedAt());
    }

    private static String normalizeStageKey(String explicitKey, String stageName) {
        String raw = explicitKey != null && !explicitKey.isBlank() ? explicitKey : stageName;
        return raw.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static class CustomerAccumulator {
        private final String customerId;
        private final List<String> emails = new ArrayList<>();
        private final List<String> phones = new ArrayList<>();
        private long ticketCount;
        private long activeJtbdCount;

        private CustomerAccumulator(String customerId) {
            this.customerId = customerId;
        }

        private String customerId() {
            return customerId;
        }

        private CustomerSummaryDto toDto() {
            return new CustomerSummaryDto(customerId, emails, phones, ticketCount, activeJtbdCount);
        }
    }
}
