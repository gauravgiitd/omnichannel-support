package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.IdentifierType;
import com.omnichannel.support.domain.PendingSelectionType;
import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskType;
import com.omnichannel.support.domain.ExecutionTier;
import com.omnichannel.support.domain.TaskPriority;
import com.omnichannel.support.dto.CreateTaskRequest;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.InboundEmailAttachment;
import com.omnichannel.support.dto.InboundEmailRequest;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.TaskDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TaskRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InboundEmailService {

    private static final Pattern SUBJECT_TICKET = Pattern.compile("(?i)\\b(TSK-[A-Z0-9-]+)\\b");
    private static final int MAX_SELECTION_OPTIONS = 5;

    private final IdentityResolutionService identityResolutionService;
    private final CustomerContactMappingService customerContactMappingService;
    private final TaskRepository taskRepository;
    private final MessageRepository messageRepository;
    private final TaskResolutionService taskResolutionService;
    private final ConversationService conversationService;
    private final CustomerConversationService customerConversationService;
    private final TaskService taskService;
    private final DocumentService documentService;
    private final JtbdService jtbdService;
    private final CustomerConversationContextService customerConversationContextService;
    private final AssignmentService assignmentService;
    private final DocumentLinkService documentLinkService;
    private final RoutingService routingService;
    private final AuditService auditService;
    private final InboundMessageUnderstandingService inboundMessageUnderstandingService;

    @Transactional
    public InboundEmailResult ingest(InboundEmailRequest request) {
        String customerId = resolveCustomerId(request);
        identityResolutionService.registerLink(customerId, IdentifierType.EMAIL, request.fromAddress());
        registerPolicyClaimHints(customerId, request);
        List<Task> openTasks = taskService.findOpenTasksForCustomer(customerId).stream()
                .map(taskResolutionService::resolveCanonical)
                .distinct()
                .toList();
        List<CustomerJtbd> activeJtbds = jtbdService.activeJtbdsForCustomer(customerId);

        if (Boolean.TRUE.equals(request.forceNewTask())) {
            customerConversationContextService.clearActiveTask(customerId, ChannelType.EMAIL);
            customerConversationContextService.clearActiveJtbd(customerId, ChannelType.EMAIL);
            return appendToConversation(customerId, null, request, null);
        }

        Optional<Task> explicitThread = resolveThreadTask(request);
        if (explicitThread.isPresent()) {
            Task task = taskResolutionService.resolveCanonical(explicitThread.get());
            assertCustomerOwns(customerId, task);
            customerConversationContextService.setActiveTask(customerId, ChannelType.EMAIL, task.getTaskNumber());
            return appendToTask(task, customerId, request);
        }

        Optional<CustomerConversationContextService.SelectionMatch> selectionMatch =
                customerConversationContextService.matchPendingSelection(customerId, ChannelType.EMAIL, request.bodyText());
        if (selectionMatch.isPresent()) {
            customerConversationContextService.clearPendingSelection(customerId, ChannelType.EMAIL);
            if (selectionMatch.get().type() == PendingSelectionType.TICKET) {
                Task task = taskService.loadCanonicalTask(selectionMatch.get().option().reference());
                assertCustomerOwns(customerId, task);
                customerConversationContextService.setActiveTask(customerId, ChannelType.EMAIL, task.getTaskNumber());
                return appendToTask(task, customerId, request);
            }
            CustomerJtbd jtbd = jtbdService.loadCustomerJtbd(selectionMatch.get().option().reference());
            if (!jtbd.getCustomerId().equals(customerId)) {
                throw new ValidationException("JTBD does not belong to resolved customer");
            }
            customerConversationContextService.setActiveJtbd(customerId, ChannelType.EMAIL, jtbd.getPublicId());
            return appendToConversation(customerId, jtbd, request, null);
        }

        Optional<CustomerConversationContextService.PendingSelection> pendingSelection =
                customerConversationContextService.pendingSelection(customerId, ChannelType.EMAIL);
        if (pendingSelection.isPresent()) {
            return appendToConversation(customerId, null, request, null);
        }

        if (isSwitchToJtbdRequest(request.bodyText()) && !activeJtbds.isEmpty()) {
            if (activeJtbds.size() == 1) {
                customerConversationContextService.setActiveJtbd(customerId, ChannelType.EMAIL, activeJtbds.get(0).getPublicId());
                return appendToConversation(customerId, activeJtbds.get(0), request, null);
            }
            List<CustomerConversationContextService.SelectionOption> options = buildJtbdSelectionOptions(activeJtbds);
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.EMAIL, PendingSelectionType.JTBD, options);
            return appendToConversation(customerId, null, request, null);
        }

        Optional<CustomerJtbd> activeJtbd = activeContextJtbd(customerId);
        if (activeJtbd.isPresent()) {
            InboundMessageUnderstandingService.InboundDecision decision =
                    inboundMessageUnderstandingService.analyze(
                            request.bodyText(),
                            request.claimIdHint(),
                            request.policyIdHint(),
                            request.attachments() != null && !request.attachments().isEmpty(),
                            activeJtbds);
            return appendToConversation(customerId, activeJtbd.get(), request, decision.assignedQueue());
        }

        InboundMessageUnderstandingService.InboundDecision decision =
                inboundMessageUnderstandingService.analyze(
                        request.bodyText(),
                        request.claimIdHint(),
                        request.policyIdHint(),
                        request.attachments() != null && !request.attachments().isEmpty(),
                        activeJtbds);
        if (decision.matchedJtbd() != null) {
            customerConversationContextService.setActiveJtbd(
                    customerId, ChannelType.EMAIL, decision.matchedJtbd().getPublicId());
            return appendToConversation(customerId, decision.matchedJtbd(), request, decision.assignedQueue());
        }

        if (!activeJtbds.isEmpty()) {
            if (activeJtbds.size() == 1) {
                customerConversationContextService.setActiveJtbd(customerId, ChannelType.EMAIL, activeJtbds.get(0).getPublicId());
                return appendToConversation(customerId, activeJtbds.get(0), request, decision.assignedQueue());
            }
            List<CustomerConversationContextService.SelectionOption> options = buildJtbdSelectionOptions(activeJtbds);
            customerConversationContextService.setPendingSelection(
                    customerId, ChannelType.EMAIL, PendingSelectionType.JTBD, options);
            return appendToConversation(customerId, null, request, decision.assignedQueue());
        }

        return appendToConversation(customerId, null, request, decision.assignedQueue());
    }

    private Optional<Task> activeContextTask(String customerId) {
        return customerConversationContextService.activeTaskNumber(customerId, ChannelType.EMAIL)
                .flatMap(taskRepository::findByTaskNumber)
                .map(taskResolutionService::resolveCanonical)
                .filter(task -> task.getCustomerId().equals(customerId))
                .filter(task -> taskService.findOpenTasksForCustomer(customerId).stream()
                        .anyMatch(open -> open.getTaskNumber().equals(task.getTaskNumber())));
    }

    private Optional<CustomerJtbd> activeContextJtbd(String customerId) {
        return customerConversationContextService.activeCustomerJtbdPublicId(customerId, ChannelType.EMAIL)
                .map(jtbdService::loadCustomerJtbd)
                .filter(jtbd -> jtbd.getCustomerId().equals(customerId));
    }

    private InboundEmailResult appendToTask(Task canonical, String customerId, InboundEmailRequest request) {
        List<DocumentDto> documents = registerEmailAttachments(canonical, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> fileUrls = documents.stream().map(DocumentDto::fileUrl).toList();
        Map<String, Object> metadata = buildEmailMetadata(request);
        if (!docIds.isEmpty()) {
            metadata.put("attachment_ids", docIds);
        }
        MessageDto message =
                conversationService.appendMessage(
                        canonical,
                        ChannelType.EMAIL,
                        SenderType.CUSTOMER,
                        request.fromAddress(),
                        request.bodyText(),
                        fileUrls,
                        normalizeMessageId(request.messageId()),
                        metadata);
        customerConversationContextService.setActiveTask(customerId, ChannelType.EMAIL, canonical.getTaskNumber());
        linkDocumentsToMessage(documents, message, canonical.getCustomerJtbd(), canonical);
        auditService.record(
                "INBOUND_EMAIL_APPENDED",
                "Task",
                canonical.getTaskNumber(),
                "SYSTEM",
                "email-adapter",
                Map.of("message_id", message.messageId()));
        return new InboundEmailResult(canonical.getTaskNumber(), message.messageId(), InboundOutcome.APPENDED);
    }

    private InboundEmailResult appendToConversation(
            String customerId, CustomerJtbd customerJtbd, InboundEmailRequest request, String assignedGroup) {
        Conversation conversation = customerConversationService.getOrCreate(customerId, ChannelType.EMAIL);
        List<DocumentDto> documents = registerEmailAttachments(conversation, customerJtbd, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> fileUrls = documents.stream().map(DocumentDto::fileUrl).toList();
        Map<String, Object> metadata = buildEmailMetadata(request);
        if (customerJtbd != null) {
            metadata.put("customer_jtbd_id", customerJtbd.getPublicId());
        }
        if (!docIds.isEmpty()) {
            metadata.put("attachment_ids", docIds);
        }
        MessageDto message =
                conversationService.appendMessage(
                        conversation,
                        customerJtbd,
                        null,
                        ChannelType.EMAIL,
                        SenderType.CUSTOMER,
                        request.fromAddress(),
                        request.bodyText(),
                        fileUrls,
                        normalizeMessageId(request.messageId()),
                        metadata);
        if (customerJtbd != null) {
            customerConversationContextService.setActiveJtbd(customerId, ChannelType.EMAIL, customerJtbd.getPublicId());
        } else {
            customerConversationContextService.clearActiveTask(customerId, ChannelType.EMAIL);
            customerConversationContextService.clearActiveJtbd(customerId, ChannelType.EMAIL);
        }
        linkDocumentsToMessage(documents, message, customerJtbd, null);
        auditService.record(
                "INBOUND_EMAIL_CONVERSATION_APPENDED",
                "Conversation",
                conversation.getPublicId(),
                "SYSTEM",
                "email-adapter",
                Map.of("message_id", message.messageId()));
        assignmentService.assign(
                conversation,
                messageRepository.findByPublicId(message.messageId()).orElse(null),
                assignedGroup != null ? assignedGroup : deriveAssignedGroup(request, customerJtbd),
                null);
        return new InboundEmailResult(null, message.messageId(), InboundOutcome.APPENDED);
    }

    private InboundEmailResult createExpertTask(
            InboundEmailRequest request,
            String customerId,
            CustomerJtbd customerJtbd,
            InboundMessageUnderstandingService.InboundDecision decision) {
        Map<String, Object> metadata = buildEmailMetadata(request);
        metadata.put("customer_jtbd_id", customerJtbd.getPublicId());
        metadata.put("jtbd_type", customerJtbd.getJtbdType().getName());
        Task task = taskService.createInternalTask(
                new CreateTaskRequest(
                        customerId,
                        decision.taskIssueType() != null ? decision.taskIssueType() : "expert_follow_up",
                        decision.domain().name().toLowerCase(Locale.ROOT),
                        blankToNull(request.claimIdHint()),
                        blankToNull(request.policyIdHint()),
                        TaskPriority.MEDIUM,
                        ChannelType.EMAIL,
                        request.bodyText(),
                        request.fromAddress(),
                        metadata,
                        normalizeMessageId(request.messageId())),
                customerJtbd,
                TaskType.EXPERT_TASK,
                ExecutionTier.EXPERT,
                decision.assignedQueue());

        List<DocumentDto> documents = registerEmailAttachments(task, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> fileUrls = documents.stream().map(DocumentDto::fileUrl).toList();
        conversationService.enrichLatestMessageWithInboundFiles(task, fileUrls, docIds);
        MessageDto latest = latestMessage(task);
        linkDocumentsToMessage(documents, latest, customerJtbd, task);
        customerConversationContextService.setActiveJtbd(customerId, ChannelType.EMAIL, customerJtbd.getPublicId());
        auditService.record(
                "INBOUND_EMAIL_EXPERT_TASK_CREATED",
                "Task",
                task.getTaskNumber(),
                "SYSTEM",
                "email-adapter",
                Map.of("customer_jtbd_id", customerJtbd.getPublicId()));
        return new InboundEmailResult(task.getTaskNumber(), latest != null ? latest.messageId() : null, InboundOutcome.CREATED);
    }

    private MessageDto latestMessage(Task task) {
        List<MessageDto> timeline = conversationService.listTimeline(task);
        return timeline.isEmpty() ? null : timeline.get(timeline.size() - 1);
    }

    private InboundEmailResult createNewTask(InboundEmailRequest request, String customerId, CustomerJtbd customerJtbd) {
        Map<String, Object> metadata = buildEmailMetadata(request);
        if (customerJtbd != null) {
            metadata.put("customer_jtbd_id", customerJtbd.getPublicId());
            metadata.put("jtbd_type", customerJtbd.getJtbdType().getName());
        }
        CreateTaskRequest create =
                new CreateTaskRequest(
                        customerId,
                        IssueTypeParser.fromEmail(request.subject(), request.bodyText()),
                        blankToNull(request.lobHint()),
                        blankToNull(request.claimIdHint()),
                        blankToNull(request.policyIdHint()),
                        TaskPriority.MEDIUM,
                        ChannelType.EMAIL,
                        request.bodyText(),
                        request.fromAddress(),
                        metadata,
                        normalizeMessageId(request.messageId()));
        TaskDto taskDto = taskService.createTask(create, customerJtbd);
        Task task =
                taskRepository
                        .findByTaskNumber(taskDto.taskId())
                        .map(taskResolutionService::resolveCanonical)
                        .orElseThrow(() -> new ValidationException("task not found after create"));

        List<DocumentDto> documents = registerEmailAttachments(task, customerId, request);
        List<String> docIds = documents.stream().map(DocumentDto::documentId).toList();
        List<String> fileUrls = documents.stream().map(DocumentDto::fileUrl).toList();
        conversationService.enrichLatestMessageWithInboundFiles(task, fileUrls, docIds);
        linkDocumentsToMessage(documents, latestMessage(task), customerJtbd, task);
        customerConversationContextService.setActiveTask(customerId, ChannelType.EMAIL, task.getTaskNumber());

        auditService.record(
                "INBOUND_EMAIL_NEW_TICKET",
                "Task",
                taskDto.taskId(),
                "SYSTEM",
                "email-adapter",
                customerJtbd != null ? Map.of("customer_jtbd_id", customerJtbd.getPublicId()) : Map.of());
        return new InboundEmailResult(taskDto.taskId(), null, InboundOutcome.CREATED);
    }

    private void registerPolicyClaimHints(String customerId, InboundEmailRequest request) {
        if (request.policyIdHint() != null && !request.policyIdHint().isBlank()) {
            identityResolutionService.registerLink(
                    customerId, IdentifierType.POLICY_ID, request.policyIdHint().trim());
        }
        if (request.claimIdHint() != null && !request.claimIdHint().isBlank()) {
            identityResolutionService.registerLink(
                    customerId, IdentifierType.CLAIM_ID, request.claimIdHint().trim());
        }
    }

    private List<DocumentDto> registerEmailAttachments(
            Task canonical, String customerId, InboundEmailRequest request) {
        if (request.attachments() == null || request.attachments().isEmpty()) {
            return List.of();
        }
        String claimHint = blankToNull(request.claimIdHint());
        String policyHint = blankToNull(request.policyIdHint());
        List<DocumentDto> documents = new ArrayList<>();
        for (InboundEmailAttachment a : request.attachments()) {
            Map<String, Object> meta = new HashMap<>();
            if (a.fileName() != null) {
                meta.put("file_name", a.fileName());
            }
            if (a.mimeType() != null) {
                meta.put("mime_type", a.mimeType());
            }
            addDriveMetadata(meta, a.fileUrl());
            meta.put("source", "email_inbound");
            String docType =
                    a.documentType() != null && !a.documentType().isBlank()
                            ? a.documentType()
                            : "email_attachment";
            DocumentDto d =
                    documentService.register(
                            canonical,
                            customerId,
                            ChannelType.EMAIL,
                            a.fileUrl(),
                            docType,
                            claimHint,
                            policyHint,
                            meta);
            documents.add(d);
        }
        return documents;
    }

    private List<DocumentDto> registerEmailAttachments(
            Conversation conversation, CustomerJtbd customerJtbd, String customerId, InboundEmailRequest request) {
        if (request.attachments() == null || request.attachments().isEmpty()) {
            return List.of();
        }
        String claimHint = blankToNull(request.claimIdHint());
        String policyHint = blankToNull(request.policyIdHint());
        List<DocumentDto> documents = new ArrayList<>();
        for (InboundEmailAttachment a : request.attachments()) {
            Map<String, Object> meta = new HashMap<>();
            if (a.fileName() != null) {
                meta.put("file_name", a.fileName());
            }
            if (a.mimeType() != null) {
                meta.put("mime_type", a.mimeType());
            }
            addDriveMetadata(meta, a.fileUrl());
            meta.put("source", "email_inbound");
            String docType =
                    a.documentType() != null && !a.documentType().isBlank()
                            ? a.documentType()
                            : "email_attachment";
            documents.add(documentService.register(
                    conversation,
                    customerJtbd,
                    null,
                    customerId,
                    ChannelType.EMAIL,
                    a.fileUrl(),
                    docType,
                    claimHint,
                    policyHint,
                    meta));
        }
        return documents;
    }

    private static void assertCustomerOwns(String customerId, Task task) {
        if (!task.getCustomerId().equals(customerId)) {
            throw new ValidationException("task does not belong to resolved customer");
        }
    }

    private String deriveAssignedGroup(InboundEmailRequest request, CustomerJtbd customerJtbd) {
        if (customerJtbd != null) {
            return taskRepository.findByCustomerJtbdIdOrderByCreatedAtDesc(customerJtbd.getId()).stream()
                    .map(Task::getAssignedQueue)
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(routingService.resolveQueue(
                            RoutingContext.builder()
                                    .issueType(customerJtbd.getJtbdType().getName())
                                    .lob(blankToNull(request.lobHint()))
                                    .claimId(blankToNull(request.claimIdHint()))
                                    .policyId(blankToNull(request.policyIdHint()))
                                    .build()));
        }
        return routingService.resolveQueue(
                RoutingContext.builder()
                        .issueType(IssueTypeParser.fromEmail(request.subject(), request.bodyText()))
                        .lob(blankToNull(request.lobHint()))
                        .claimId(blankToNull(request.claimIdHint()))
                        .policyId(blankToNull(request.policyIdHint()))
                        .build());
    }

    private void linkDocumentsToMessage(
            List<DocumentDto> documents, MessageDto message, CustomerJtbd customerJtbd, Task task) {
        if (documents == null || documents.isEmpty() || message == null) {
            return;
        }
        var savedMessage = messageRepository.findByPublicId(message.messageId()).orElse(null);
        for (DocumentDto document : documents) {
            documentLinkService.link(
                    documentService.getByPublicId(document.documentId()),
                    null,
                    savedMessage,
                    customerJtbd,
                    task);
        }
    }

    private String resolveCustomerId(InboundEmailRequest request) {
        if (request.customerIdHint() != null && !request.customerIdHint().isBlank()) {
            return request.customerIdHint().trim();
        }
        Optional<String> direct = identityResolutionService.resolveCustomerId(IdentifierType.EMAIL, request.fromAddress());
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<String> mappedPhone = customerContactMappingService.counterpartForEmail(request.fromAddress());
        if (mappedPhone.isPresent()) {
            Optional<String> mappedCustomer = identityResolutionService.resolveCustomerId(IdentifierType.PHONE, mappedPhone.get());
            if (mappedCustomer.isPresent()) {
                return mappedCustomer.get();
            }
        }
        return provisionCustomerForNewEmail(request.fromAddress());
    }

    private String provisionCustomerForNewEmail(String fromAddress) {
        String newId = "CUST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        identityResolutionService.registerLink(newId, IdentifierType.EMAIL, fromAddress);
        return newId;
    }

    private Optional<Task> resolveThreadTask(InboundEmailRequest request) {
        if (request.inReplyTo() != null && !request.inReplyTo().isBlank()) {
            Optional<Task> byReply = findTaskByMessageRef(normalizeMessageId(request.inReplyTo()));
            if (byReply.isPresent()) {
                return byReply;
            }
        }
        if (request.references() != null) {
            for (String reference : request.references()) {
                Optional<Task> task = findTaskByMessageRef(normalizeMessageId(reference));
                if (task.isPresent()) {
                    return task;
                }
            }
        }
        if (request.subject() != null) {
            Matcher matcher = SUBJECT_TICKET.matcher(request.subject());
            if (matcher.find()) {
                String taskNumber = matcher.group(1).toUpperCase(Locale.ROOT);
                return taskRepository.findByTaskNumber(taskNumber);
            }
        }
        if (request.bodyText() != null) {
            Matcher matcher = SUBJECT_TICKET.matcher(request.bodyText());
            if (matcher.find()) {
                String taskNumber = matcher.group(1).toUpperCase(Locale.ROOT);
                return taskRepository.findByTaskNumber(taskNumber);
            }
        }
        return Optional.empty();
    }

    private static boolean isSwitchToTaskRequest(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toUpperCase(Locale.ROOT);
        return normalized.contains("SWITCH TICKET")
                || normalized.contains("CHANGE TICKET")
                || normalized.contains("ANOTHER TICKET")
                || normalized.contains("DIFFERENT TICKET");
    }

    private static boolean isSwitchToJtbdRequest(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = body.toUpperCase(Locale.ROOT);
        return normalized.contains("SWITCH JOB")
                || normalized.contains("CHANGE JOB")
                || normalized.contains("ANOTHER JOB")
                || normalized.contains("DIFFERENT JOB")
                || normalized.contains("SWITCH JTBD")
                || normalized.contains("CHANGE JTBD");
    }

    private static List<CustomerConversationContextService.SelectionOption> buildTaskSelectionOptions(List<Task> openTasks) {
        List<CustomerConversationContextService.SelectionOption> options = new ArrayList<>();
        for (int i = 0; i < Math.min(openTasks.size(), MAX_SELECTION_OPTIONS); i++) {
            Task task = openTasks.get(i);
            String label = task.getCustomerJtbd() != null
                    ? task.getCustomerJtbd().getJtbdType().getName() + " - " + task.getCustomerJtbd().getCurrentStage().getStageName()
                    : humanize(task.getIssueType()) + " - " + humanize(task.getStatus().name());
            options.add(new CustomerConversationContextService.SelectionOption(
                    i + 1,
                    task.getTaskNumber(),
                    label));
        }
        return options;
    }

    private static List<CustomerConversationContextService.SelectionOption> buildJtbdSelectionOptions(List<CustomerJtbd> activeJtbds) {
        List<CustomerConversationContextService.SelectionOption> options = new ArrayList<>();
        for (int i = 0; i < Math.min(activeJtbds.size(), MAX_SELECTION_OPTIONS); i++) {
            CustomerJtbd jtbd = activeJtbds.get(i);
            options.add(new CustomerConversationContextService.SelectionOption(
                    i + 1,
                    jtbd.getPublicId(),
                    jtbd.getJtbdType().getName() + " - " + jtbd.getCurrentStage().getStageName()));
        }
        return options;
    }

    private static String buildPromptBody(
            PendingSelectionType type, List<CustomerConversationContextService.SelectionOption> options) {
        String topic = type == PendingSelectionType.TICKET ? "request" : "job";
        StringBuilder builder = new StringBuilder("We found multiple ").append(topic)
                .append(" options for your account. Reply with the number for the one you mean:\n");
        for (CustomerConversationContextService.SelectionOption option : options) {
            builder.append(option.optionNumber())
                    .append(". ")
                    .append(option.label())
                    .append("\n");
        }
        builder.append("We will keep the conversation on that selection until you ask about another one.");
        return builder.toString();
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) {
            return "Support request";
        }
        String normalized = value.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private Optional<Task> findTaskByMessageRef(String normalizedId) {
        if (normalizedId == null || normalizedId.isBlank()) {
            return Optional.empty();
        }
        return messageRepository
                .findByExternalThreadRef(normalizedId)
                .map(m -> taskResolutionService.resolveCanonical(m.getTask()));
    }

    private static Map<String, Object> buildEmailMetadata(InboundEmailRequest request) {
        Map<String, Object> meta = new HashMap<>();
        if (request.messageId() != null) {
            meta.put("email_message_id", normalizeMessageId(request.messageId()));
        }
        if (request.inReplyTo() != null) {
            meta.put("in_reply_to", normalizeMessageId(request.inReplyTo()));
        }
        if (request.subject() != null) {
            meta.put("subject", request.subject());
        }
        return meta;
    }

    private static String normalizeMessageId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void addDriveMetadata(Map<String, Object> metadata, String fileUrl) {
        DriveFileToken token = DriveFileToken.parse(fileUrl);
        if (token != null) {
            metadata.put("drive_file_id", token.fileId());
            if (token.fileName() != null && !token.fileName().isBlank()) {
                metadata.put("file_name", token.fileName());
            }
            if (token.mimeType() != null && !token.mimeType().isBlank()) {
                metadata.put("mime_type", token.mimeType());
            }
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private record DriveFileToken(String fileId, String fileName, String mimeType) {
        static DriveFileToken parse(String raw) {
            if (raw == null || !raw.startsWith("drive://")) {
                return null;
            }
            String remainder = raw.substring("drive://".length());
            String fileId = remainder;
            String fileName = null;
            String mimeType = null;
            int queryIndex = remainder.indexOf('?');
            if (queryIndex >= 0) {
                fileId = remainder.substring(0, queryIndex);
                String query = remainder.substring(queryIndex + 1);
                for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = java.net.URLDecoder.decode(part.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8);
                    String value = java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                    if ("name".equals(key)) {
                        fileName = value;
                    } else if ("mime".equals(key)) {
                        mimeType = value;
                    }
                }
            }
            return new DriveFileToken(fileId, fileName, mimeType);
        }
    }

    public enum InboundOutcome {
        CREATED,
        APPENDED,
        PROMPTED
    }

    public record InboundEmailResult(String taskNumber, String messageId, InboundOutcome outcome) {}
}
