package com.omnichannel.support.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.config.WhatsAppCloudApiProperties;
import com.omnichannel.support.dto.InboundWhatsAppRequest;
import com.omnichannel.support.repo.MessageRepository;
import java.util.ArrayList;
import java.util.List;
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

        for (JsonNode entry : payload.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
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
            }
        }

        return new MetaWebhookResult(processedMessages);
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

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public record MetaWebhookResult(int processedMessages) {}
}
