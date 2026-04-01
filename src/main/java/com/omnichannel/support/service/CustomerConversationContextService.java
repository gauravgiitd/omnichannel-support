package com.omnichannel.support.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.CustomerConversationContext;
import com.omnichannel.support.domain.PendingSelectionType;
import com.omnichannel.support.repo.CustomerConversationContextRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerConversationContextService {

    private final CustomerConversationContextRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Optional<String> activeTaskNumber(String customerId, ChannelType channel) {
        return repository.findByCustomerIdAndChannel(customerId, channel)
                .map(CustomerConversationContext::getActiveTaskNumber)
                .filter(value -> value != null && !value.isBlank());
    }

    @Transactional(readOnly = true)
    public Optional<String> activeCustomerJtbdPublicId(String customerId, ChannelType channel) {
        return repository.findByCustomerIdAndChannel(customerId, channel)
                .map(CustomerConversationContext::getActiveCustomerJtbdPublicId)
                .filter(value -> value != null && !value.isBlank());
    }

    @Transactional
    public void setActiveTask(String customerId, ChannelType channel, String taskNumber) {
        CustomerConversationContext context = loadOrCreate(customerId, channel);
        context.setActiveTaskNumber(taskNumber);
        context.setPendingSelectionType(null);
        context.setPendingOptionsJson(null);
        repository.save(context);
    }

    @Transactional
    public void setActiveJtbd(String customerId, ChannelType channel, String customerJtbdPublicId) {
        CustomerConversationContext context = loadOrCreate(customerId, channel);
        context.setActiveTaskNumber(null);
        context.setActiveCustomerJtbdPublicId(customerJtbdPublicId);
        context.setPendingSelectionType(null);
        context.setPendingOptionsJson(null);
        repository.save(context);
    }

    @Transactional
    public void clearActiveTask(String customerId, ChannelType channel) {
        repository.findByCustomerIdAndChannel(customerId, channel).ifPresent(context -> {
            context.setActiveTaskNumber(null);
            repository.save(context);
        });
    }

    @Transactional
    public void clearActiveJtbd(String customerId, ChannelType channel) {
        repository.findByCustomerIdAndChannel(customerId, channel).ifPresent(context -> {
            context.setActiveCustomerJtbdPublicId(null);
            repository.save(context);
        });
    }

    @Transactional
    public void setPendingSelection(
            String customerId, ChannelType channel, PendingSelectionType type, List<SelectionOption> options) {
        CustomerConversationContext context = loadOrCreate(customerId, channel);
        context.setActiveTaskNumber(null);
        context.setActiveCustomerJtbdPublicId(null);
        context.setPendingSelectionType(type);
        context.setPendingOptionsJson(toJson(options));
        repository.save(context);
    }

    @Transactional(readOnly = true)
    public Optional<PendingSelection> pendingSelection(String customerId, ChannelType channel) {
        return repository.findByCustomerIdAndChannel(customerId, channel)
                .filter(context -> context.getPendingSelectionType() != null)
                .map(context -> new PendingSelection(
                        context.getPendingSelectionType(),
                        parseOptions(context.getPendingOptionsJson())));
    }

    @Transactional
    public void clearPendingSelection(String customerId, ChannelType channel) {
        repository.findByCustomerIdAndChannel(customerId, channel).ifPresent(context -> {
            context.setPendingSelectionType(null);
            context.setPendingOptionsJson(null);
            repository.save(context);
        });
    }

    @Transactional(readOnly = true)
    public Optional<SelectionMatch> matchPendingSelection(String customerId, ChannelType channel, String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        Optional<PendingSelection> pending = pendingSelection(customerId, channel);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        String normalized = body.trim().toUpperCase(Locale.ROOT);
        for (SelectionOption option : pending.get().options()) {
            if (normalized.equals(String.valueOf(option.optionNumber()))
                    || normalized.contains(option.reference().toUpperCase(Locale.ROOT))) {
                return Optional.of(new SelectionMatch(pending.get().type(), option));
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void clearTaskReferences(String taskNumber) {
        repository.findByActiveTaskNumber(taskNumber).forEach(context -> {
            context.setActiveTaskNumber(null);
            repository.save(context);
        });
    }

    private CustomerConversationContext loadOrCreate(String customerId, ChannelType channel) {
        return repository.findByCustomerIdAndChannel(customerId, channel).orElseGet(() -> {
            CustomerConversationContext context = new CustomerConversationContext();
            context.setCustomerId(customerId);
            context.setChannel(channel);
            return context;
        });
    }

    private String toJson(List<SelectionOption> options) {
        try {
            return objectMapper.writeValueAsString(options);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private List<SelectionOption> parseOptions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    public record SelectionOption(int optionNumber, String reference, String label) {}

    public record PendingSelection(PendingSelectionType type, List<SelectionOption> options) {}

    public record SelectionMatch(PendingSelectionType type, SelectionOption option) {}
}
