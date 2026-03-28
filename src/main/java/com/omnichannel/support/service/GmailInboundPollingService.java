package com.omnichannel.support.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnichannel.support.config.GmailPollingProperties;
import com.omnichannel.support.config.SupportPlatformProperties;
import com.omnichannel.support.dto.InboundEmailAttachment;
import com.omnichannel.support.dto.InboundEmailRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GmailInboundPollingService {

    private static final Logger log = LoggerFactory.getLogger(GmailInboundPollingService.class);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private final GmailPollingProperties properties;
    private final SupportPlatformProperties supportPlatformProperties;
    private final GmailApiClient gmailApiClient;
    private final InboundEmailService inboundEmailService;

    @Scheduled(fixedDelayString = "${support.gmail-polling.fixed-delay-ms:30000}")
    public void pollInbox() {
        if (!properties.isEnabled() || !gmailApiClient.isConfigured()) {
            return;
        }
        try {
            String query = effectiveQuery();
            for (String messageId : gmailApiClient.listUnreadInboxMessageIds(query, properties.getMaxResults())) {
                processMessage(messageId);
            }
        } catch (Exception ex) {
            log.error("Failed polling Gmail inbox", ex);
        }
    }

    private void processMessage(String gmailMessageId) {
        try {
            JsonNode message = gmailApiClient.getMessage(gmailMessageId);
            EmailEnvelope envelope = EmailEnvelope.from(message, supportPlatformProperties.outboundEmail().fromAddress());
            if (envelope == null) {
                gmailApiClient.markProcessed(gmailMessageId);
                return;
            }

            inboundEmailService.ingest(new InboundEmailRequest(
                    envelope.fromAddress(),
                    envelope.toAddress(),
                    envelope.subject(),
                    envelope.bodyText(),
                    envelope.messageId(),
                    envelope.inReplyTo(),
                    envelope.references(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    envelope.attachments()));

            gmailApiClient.markProcessed(gmailMessageId);
        } catch (Exception ex) {
            log.error("Failed processing Gmail message {}", gmailMessageId, ex);
        }
    }

    private String effectiveQuery() {
        if (properties.getQuery() != null && !properties.getQuery().isBlank()) {
            return properties.getQuery().trim();
        }
        String supportAddress = supportPlatformProperties.outboundEmail().fromAddress();
        return "in:inbox is:unread -from:" + supportAddress;
    }

    private record EmailEnvelope(
            String fromAddress,
            String toAddress,
            String subject,
            String bodyText,
            String messageId,
            String inReplyTo,
            List<String> references,
            List<InboundEmailAttachment> attachments) {

        static EmailEnvelope from(JsonNode message, String supportAddress) {
            JsonNode payload = message.path("payload");
            String from = header(payload, "From");
            String to = header(payload, "To");
            if (from == null || from.isBlank()) {
                return null;
            }
            String normalizedFrom = extractEmail(from);
            if (supportAddress != null && supportAddress.equalsIgnoreCase(normalizedFrom)) {
                return null;
            }
            String subject = header(payload, "Subject");
            String messageId = header(payload, "Message-ID");
            String inReplyTo = header(payload, "In-Reply-To");
            List<String> references = parseReferences(header(payload, "References"));

            BodyAndAttachments parsed = readPayload(payload, message.path("id").asText(""));
            String body = parsed.bodyText() != null && !parsed.bodyText().isBlank()
                    ? parsed.bodyText()
                    : (message.path("snippet").asText(""));

            return new EmailEnvelope(
                    normalizedFrom,
                    extractEmail(to),
                    subject,
                    body,
                    messageId,
                    inReplyTo,
                    references,
                    parsed.attachments());
        }

        private static String header(JsonNode payload, String name) {
            for (JsonNode header : payload.path("headers")) {
                if (name.equalsIgnoreCase(header.path("name").asText())) {
                    return header.path("value").asText(null);
                }
            }
            return null;
        }

        private static List<String> parseReferences(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(raw.trim().split("\\s+"))
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        private static String extractEmail(String raw) {
            if (raw == null || raw.isBlank()) {
                return "";
            }
            int start = raw.indexOf('<');
            int end = raw.indexOf('>');
            if (start >= 0 && end > start) {
                return raw.substring(start + 1, end).trim().toLowerCase(Locale.ROOT);
            }
            return raw.trim().toLowerCase(Locale.ROOT);
        }

        private static BodyAndAttachments readPayload(JsonNode payload, String gmailMessageId) {
            List<InboundEmailAttachment> attachments = new ArrayList<>();
            String plain = extractBody(payload, "text/plain");
            String html = extractBody(payload, "text/html");
            collectAttachments(payload, gmailMessageId, attachments);
            String body = plain != null && !plain.isBlank() ? plain : stripHtml(html);
            return new BodyAndAttachments(body, attachments);
        }

        private static String extractBody(JsonNode part, String preferredMimeType) {
            String mimeType = part.path("mimeType").asText("");
            if (preferredMimeType.equalsIgnoreCase(mimeType)) {
                String data = part.path("body").path("data").asText("");
                if (!data.isBlank()) {
                    return decode(data);
                }
            }
            for (JsonNode child : part.path("parts")) {
                String body = extractBody(child, preferredMimeType);
                if (body != null && !body.isBlank()) {
                    return body;
                }
            }
            return null;
        }

        private static void collectAttachments(JsonNode part, String gmailMessageId, List<InboundEmailAttachment> out) {
            String filename = part.path("filename").asText("");
            String attachmentId = part.path("body").path("attachmentId").asText("");
            if (!filename.isBlank() && !attachmentId.isBlank()) {
                String mimeType = part.path("mimeType").asText("");
                out.add(new InboundEmailAttachment(
                        "/v1/gmail/messages/" + gmailMessageId + "/attachments/" + attachmentId + "/" + sanitize(filename),
                        filename,
                        mimeType,
                        guessDocumentType(filename, mimeType)));
            }
            for (JsonNode child : part.path("parts")) {
                collectAttachments(child, gmailMessageId, out);
            }
        }

        private static String decode(String value) {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        }

        private static String stripHtml(String html) {
            if (html == null || html.isBlank()) {
                return "";
            }
            return HTML_TAG.matcher(html).replaceAll(" ").replace("&nbsp;", " ").trim();
        }

        private static String sanitize(String value) {
            return value.replaceAll("[^A-Za-z0-9._-]", "_");
        }

        private static String guessDocumentType(String filename, String mimeType) {
            String lower = (filename == null ? "" : filename.toLowerCase(Locale.ROOT));
            if (lower.endsWith(".pdf") || "application/pdf".equalsIgnoreCase(mimeType)) {
                return "email_attachment_pdf";
            }
            if (mimeType != null && mimeType.startsWith("image/")) {
                return "email_attachment_image";
            }
            return "email_attachment";
        }
    }

    private record BodyAndAttachments(String bodyText, List<InboundEmailAttachment> attachments) {}
}
