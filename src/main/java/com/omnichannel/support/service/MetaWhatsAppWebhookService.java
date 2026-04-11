package com.omnichannel.support.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.omnichannel.support.config.WhatsAppCloudApiProperties;
import com.omnichannel.support.dto.InboundWhatsAppRequest;
import com.omnichannel.support.repo.MessageRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MetaWhatsAppWebhookService {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppWebhookService.class);

    private final ObjectMapper objectMapper;
    private final WhatsAppCloudApiProperties properties;
    private final MetaWhatsAppCloudApiClient metaWhatsAppCloudApiClient;
    private final GoogleDriveStorageService googleDriveStorageService;
    private final InboundWhatsAppService inboundWhatsAppService;
    private final MessageRepository messageRepository;
    private final AuditService auditService;
    private final WhatsAppCallingService whatsAppCallingService;

    public boolean isConfigured() {
        return properties.isEnabled()
                && hasText(properties.getVerifyToken())
                && metaWhatsAppCloudApiClient.isConfigured();
    }

    public boolean isValidVerifyToken(String verifyToken) {
        return hasText(properties.getVerifyToken()) && properties.getVerifyToken().equals(verifyToken);
    }

    public MetaWebhookResult process(String rawPayload) throws Exception {
        JsonNode payload = objectMapper.readTree(rawPayload);
        int processedMessages = 0;
        int processedCallEvents = 0;

        for (JsonNode entry : payload.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                log.info(
                        "Received Meta WhatsApp webhook change field={} keys={}",
                        blankToNull(change.path("field").asText()),
                        value.isObject() ? iterableFieldNames(value).toString() : "[]");
                if (!"whatsapp_business_account".equals(payload.path("object").asText())
                        && !"whatsapp".equals(value.path("messaging_product").asText("whatsapp"))) {
                    continue;
                }

                for (JsonNode message : value.path("messages")) {
                    if (!message.hasNonNull("id")) {
                        continue;
                    }
                    String messageId = message.path("id").asText();
                    if (messageRepository.findByExternalThreadRef(messageId).isPresent()) {
                        continue;
                    }
                    try {
                        InboundWhatsAppRequest request = toInboundRequest(message);
                        inboundWhatsAppService.ingest(request);
                        processedMessages += 1;
                    } catch (Exception ex) {
                        log.warn("Failed processing Meta WhatsApp message {}", messageId, ex);
                    }
                }

                JsonNode metadata = value.path("metadata");
                for (JsonNode call : value.path("calls")) {
                    processedCallEvents += processCallEvent(call, metadata);
                }
                for (JsonNode status : value.path("statuses")) {
                    processedCallEvents += processCallStatus(status, metadata);
                }
                processedCallEvents += processPermissionStatus(change.path("field"), value);
            }
        }

        return new MetaWebhookResult(processedMessages, processedCallEvents);
    }

    private int processCallEvent(JsonNode call, JsonNode metadata) {
        String callId = blankToNull(call.path("id").asText());
        if (callId == null) {
            return 0;
        }
        String rawCallPayload = buildRawCallPayload(call, metadata);
        log.info(
                "Received Meta WhatsApp call webhook {} event={} status={} direction={}",
                callId,
                blankToNull(call.path("event").asText()),
                blankToNull(call.path("status").asText()),
                blankToNull(call.path("direction").asText()));
        whatsAppCallingService.recordWebhookEvent(
                callId,
                blankToNull(call.path("from").asText()),
                blankToNull(call.path("to").asText()),
                blankToNull(call.path("status").asText()),
                blankToNull(call.path("direction").asText()),
                blankToNull(call.path("event").asText()),
                blankToNull(call.path("session").path("sdp_type").asText()),
                blankToNull(call.path("session").path("sdp").asText()),
                blankToNull(metadata.path("phone_number_id").asText()),
                blankToNull(metadata.path("display_phone_number").asText()),
                parseEpochSeconds(call.path("start_time").asText(null)),
                parseEpochSeconds(call.path("end_time").asText(null)),
                call.path("duration").isIntegralNumber() ? call.path("duration").asInt() : null,
                rawCallPayload);
        auditService.record(
                "WHATSAPP_CALL_EVENT",
                "WhatsAppCall",
                callId,
                "SYSTEM",
                "meta-whatsapp-webhook",
                buildCallAuditPayload(call));
        return 1;
    }

    private int processCallStatus(JsonNode status, JsonNode metadata) {
        if (!"call".equalsIgnoreCase(status.path("type").asText())) {
            return 0;
        }
        String callId = blankToNull(status.path("id").asText());
        if (callId == null) {
            return 0;
        }
        whatsAppCallingService.recordWebhookEvent(
                callId,
                blankToNull(metadata.path("display_phone_number").asText()),
                blankToNull(status.path("recipient_id").asText()),
                blankToNull(status.path("status").asText()),
                "BUSINESS_INITIATED",
                "status",
                null,
                null,
                blankToNull(metadata.path("phone_number_id").asText()),
                blankToNull(metadata.path("display_phone_number").asText()),
                null,
                null,
                null,
                buildRawCallPayload(status, metadata));
        return 1;
    }

    private int processPermissionStatus(JsonNode field, JsonNode value) {
        JsonNode payload = null;
        if ("call_permission_status".equalsIgnoreCase(field.asText())) {
            payload = value;
        } else if ("call_permission_status".equalsIgnoreCase(value.path("event").asText())) {
            payload = value;
        } else if (value.has("call_permission_status")) {
            payload = value.path("call_permission_status");
        }
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            return 0;
        }
        log.info("Received Meta WhatsApp permission status payload={}", payload.toString());
        String recipient = firstNonBlank(
                blankToNull(payload.path("recipient").asText()),
                blankToNull(payload.path("recipient_id").asText()),
                blankToNull(payload.path("phone_number").asText()),
                blankToNull(payload.path("to").asText()),
                blankToNull(payload.path("from").asText()));
        String status = firstNonBlank(
                blankToNull(payload.path("status").asText()),
                blankToNull(payload.path("permission_status").asText()),
                blankToNull(payload.path("event").asText()));
        if (recipient == null || status == null) {
            log.warn(
                    "Ignoring Meta WhatsApp permission status payload because recipient/status could not be resolved payload={}",
                    payload.toString());
            return 0;
        }
        whatsAppCallingService.recordPermissionStatus(recipient, status, "meta_webhook");
        return 1;
    }

    private String buildRawCallPayload(JsonNode call, JsonNode metadata) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("metadata", metadata == null ? objectMapper.createObjectNode() : metadata.deepCopy());
        root.set("call", call == null ? objectMapper.createObjectNode() : call.deepCopy());
        return root.toString();
    }

    private static Map<String, Object> buildCallAuditPayload(JsonNode call) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "from", blankToNull(call.path("from").asText()));
        putIfPresent(payload, "to", blankToNull(call.path("to").asText()));
        putIfPresent(payload, "status", blankToNull(call.path("status").asText()));
        putIfPresent(payload, "direction", blankToNull(call.path("direction").asText()));
        putIfPresent(payload, "event", blankToNull(call.path("event").asText()));
        putIfPresent(payload, "session_sdp_type", blankToNull(call.path("session").path("sdp_type").asText()));
        return payload;
    }

    private InboundWhatsAppRequest toInboundRequest(JsonNode message) throws Exception {
        String fromPhone = normalizePhone(message.path("from").asText());
        String messageId = message.path("id").asText();
        String replyToMessageId = blankToNull(message.path("context").path("id").asText());
        List<String> attachmentUrls = extractAttachments(message);

        String bodyText = extractBodyText(message);
        if (!hasText(bodyText)) {
            bodyText = fallbackBodyText(message, attachmentUrls);
        }

        return new InboundWhatsAppRequest(
                messageId,
                fromPhone,
                bodyText,
                replyToMessageId,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                attachmentUrls);
    }

    private List<String> extractAttachments(JsonNode message) throws Exception {
        List<String> attachments = new ArrayList<>();
        for (String type : List.of("document", "image", "video", "audio", "sticker")) {
            JsonNode mediaNode = message.path(type);
            if (!mediaNode.hasNonNull("id")) {
                continue;
            }
            String mediaId = mediaNode.path("id").asText();
            MetaWhatsAppCloudApiClient.MediaDescriptor media = metaWhatsAppCloudApiClient.getMediaMetadata(mediaId);
            if (!hasText(media.downloadUrl())) {
                continue;
            }
            byte[] bytes = metaWhatsAppCloudApiClient.downloadMedia(media.downloadUrl());
            if (googleDriveStorageService.isConfigured()) {
                String fileName = resolveFileName(type, mediaNode, media);
                String mimeType = hasText(mediaNode.path("mime_type").asText())
                        ? mediaNode.path("mime_type").asText()
                        : media.mimeType();
                GoogleDriveStorageService.StoredDriveFile storedFile =
                        googleDriveStorageService.upload(
                                fileName,
                                mimeType,
                                bytes,
                                "WhatsApp inbound attachment " + mediaId);
                attachments.add("drive://"
                        + storedFile.fileId()
                        + "?name="
                        + urlEncode(storedFile.fileName())
                        + "&mime="
                        + urlEncode(storedFile.mimeType()));
            }
        }
        return attachments;
    }

    private static String extractBodyText(JsonNode message) {
        if (message.path("text").hasNonNull("body")) {
            return message.path("text").path("body").asText();
        }
        for (String type : List.of("document", "image", "video")) {
            JsonNode node = message.path(type);
            if (node.hasNonNull("caption")) {
                return node.path("caption").asText();
            }
        }
        if (message.path("button").hasNonNull("text")) {
            return message.path("button").path("text").asText();
        }
        if (message.path("interactive").path("button_reply").hasNonNull("id")) {
            return message.path("interactive").path("button_reply").path("id").asText();
        }
        if (message.path("interactive").path("button_reply").hasNonNull("title")) {
            return message.path("interactive").path("button_reply").path("title").asText();
        }
        if (message.path("interactive").path("list_reply").hasNonNull("id")) {
            return message.path("interactive").path("list_reply").path("id").asText();
        }
        if (message.path("interactive").path("list_reply").hasNonNull("title")) {
            return message.path("interactive").path("list_reply").path("title").asText();
        }
        return null;
    }

    private static String fallbackBodyText(JsonNode message, List<String> attachmentUrls) {
        String type = message.path("type").asText("message");
        if (!attachmentUrls.isEmpty()) {
            return "Customer sent a " + type + " attachment on WhatsApp.";
        }
        return "Customer sent a " + type + " message on WhatsApp.";
    }

    private static String resolveFileName(
            String type, JsonNode mediaNode, MetaWhatsAppCloudApiClient.MediaDescriptor media) {
        if (mediaNode.hasNonNull("filename") && hasText(mediaNode.path("filename").asText())) {
            return mediaNode.path("filename").asText();
        }
        String extension = extensionForMimeType(
                hasText(mediaNode.path("mime_type").asText()) ? mediaNode.path("mime_type").asText() : media.mimeType());
        return type + "-" + media.mediaId() + extension;
    }

    private static String extensionForMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "";
        }
        if ("application/pdf".equalsIgnoreCase(mimeType)) {
            return ".pdf";
        }
        if ("image/jpeg".equalsIgnoreCase(mimeType)) {
            return ".jpg";
        }
        if ("image/png".equalsIgnoreCase(mimeType)) {
            return ".png";
        }
        if ("video/mp4".equalsIgnoreCase(mimeType)) {
            return ".mp4";
        }
        if ("audio/ogg".equalsIgnoreCase(mimeType)) {
            return ".ogg";
        }
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) {
            return ".mp3";
        }
        int slash = mimeType.indexOf('/');
        if (slash >= 0 && slash < mimeType.length() - 1) {
            return "." + mimeType.substring(slash + 1);
        }
        return "";
    }

    private static String normalizePhone(String rawPhone) {
        String trimmed = rawPhone == null ? "" : rawPhone.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("+")) {
            return trimmed;
        }
        return "+" + trimmed;
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Instant parseEpochSeconds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static java.util.List<String> iterableFieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (node == null || !node.isObject()) {
            return names;
        }
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    public record MetaWebhookResult(int processedMessages, int processedCallEvents) {}
}
