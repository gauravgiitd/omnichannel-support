package com.omnichannel.support.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnichannel.support.config.WhatsAppCloudApiProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MetaWhatsAppCloudApiClient {

    private static final Logger log = LoggerFactory.getLogger(MetaWhatsAppCloudApiClient.class);

    private final WhatsAppCloudApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean isConfigured() {
        return hasText(properties.getAccessToken());
    }

    public boolean canSendMessages() {
        return isConfigured() && hasText(properties.getPhoneNumberId());
    }

    public boolean canManageCalls() {
        return isConfigured() && hasText(properties.getPhoneNumberId()) && properties.isCallingEnabled();
    }

    public MediaDescriptor getMediaMetadata(String mediaId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        graphBaseUrl() + "/" + urlEncode(mediaId) + "?fields=id,url,mime_type,file_size,sha256"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WhatsApp media metadata request failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        return new MediaDescriptor(
                json.path("id").asText(mediaId),
                json.path("url").asText(),
                json.path("mime_type").asText(null));
    }

    public byte[] downloadMedia(String mediaUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(mediaUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WhatsApp media download failed: " + response.statusCode());
        }
        return response.body();
    }

    public String sendTextMessage(String toPhoneNumber, String body) throws IOException, InterruptedException {
        String normalizedPhone = normalizeRecipient(toPhoneNumber);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(graphBaseUrl() + "/" + urlEncode(properties.getPhoneNumberId()) + "/messages"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "messaging_product", "whatsapp",
                        "recipient_type", "individual",
                        "to", normalizedPhone,
                        "type", "text",
                        "text", Map.of("preview_url", false, "body", body)))))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WhatsApp send message failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode messages = json.path("messages");
        if (messages.isArray() && !messages.isEmpty() && messages.get(0).hasNonNull("id")) {
            return messages.get(0).path("id").asText();
        }
        return null;
    }

    public String sendInteractiveListMessage(
            String toPhoneNumber,
            String body,
            String buttonText,
            java.util.List<InteractiveListRow> rows)
            throws IOException, InterruptedException {
        String normalizedPhone = normalizeRecipient(toPhoneNumber);
        java.util.List<Map<String, Object>> rowPayload = rows.stream()
                .limit(10)
                .map(row -> {
                    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
                    payload.put("id", row.id());
                    payload.put("title", truncate(row.title(), 24));
                    if (hasText(row.description())) {
                        payload.put("description", truncate(row.description(), 72));
                    }
                    return payload;
                })
                .toList();
        Map<String, Object> interactive = Map.of(
                "type", "list",
                "body", Map.of("text", body),
                "action", Map.of(
                        "button", truncate(hasText(buttonText) ? buttonText : "Select", 20),
                        "sections", java.util.List.of(Map.of(
                                "title", "Support items",
                                "rows", rowPayload))));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(graphBaseUrl() + "/" + urlEncode(properties.getPhoneNumberId()) + "/messages"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "messaging_product", "whatsapp",
                        "recipient_type", "individual",
                        "to", normalizedPhone,
                        "type", "interactive",
                        "interactive", interactive))))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WhatsApp send interactive list failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode messages = json.path("messages");
        if (messages.isArray() && !messages.isEmpty() && messages.get(0).hasNonNull("id")) {
            return messages.get(0).path("id").asText();
        }
        return null;
    }

    public String uploadMedia(String fileName, String mimeType, byte[] bytes) throws IOException, InterruptedException {
        String boundary = "wa-media-" + System.nanoTime();
        String safeMime = hasText(mimeType) ? mimeType : "application/octet-stream";
        byte[] prefix = (
                "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"messaging_product\"\r\n\r\n"
                        + "whatsapp\r\n"
                        + "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + escapeQuoted(fileName) + "\"\r\n"
                        + "Content-Type: " + safeMime + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[prefix.length + bytes.length + suffix.length];
        System.arraycopy(prefix, 0, payload, 0, prefix.length);
        System.arraycopy(bytes, 0, payload, prefix.length, bytes.length);
        System.arraycopy(suffix, 0, payload, prefix.length + bytes.length, suffix.length);

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(graphBaseUrl() + "/" + urlEncode(properties.getPhoneNumberId()) + "/media"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WhatsApp media upload failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        return json.path("id").asText();
    }

    public String sendDocumentMessage(String toPhoneNumber, String mediaId, String fileName, String caption)
            throws IOException, InterruptedException {
        String normalizedPhone = normalizeRecipient(toPhoneNumber);
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("id", mediaId);
        if (hasText(fileName)) {
            document.put("filename", fileName);
        }
        if (hasText(caption)) {
            document.put("caption", caption);
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(graphBaseUrl() + "/" + urlEncode(properties.getPhoneNumberId()) + "/messages"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "messaging_product", "whatsapp",
                        "recipient_type", "individual",
                        "to", normalizedPhone,
                        "type", "document",
                        "document", document))))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WhatsApp send document failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode messages = json.path("messages");
        if (messages.isArray() && !messages.isEmpty() && messages.get(0).hasNonNull("id")) {
            return messages.get(0).path("id").asText();
        }
        return null;
    }

    public String sendTemplateMessage(String toPhoneNumber, String templateName, String languageCode)
            throws IOException, InterruptedException {
        String normalizedPhone = normalizeRecipient(toPhoneNumber);
        java.util.Map<String, Object> template = new java.util.LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", languageCode));
        template.put("components", java.util.List.of(
                Map.of(
                        "type", "body",
                        "parameters", java.util.List.of(
                                Map.of("type", "text", "text", "ACKO")))));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(graphBaseUrl() + "/" + urlEncode(properties.getPhoneNumberId()) + "/messages"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                        "messaging_product", "whatsapp",
                        "to", normalizedPhone,
                        "type", "template",
                        "template", template))))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("WhatsApp send template failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        JsonNode messages = json.path("messages");
        if (messages.isArray() && !messages.isEmpty() && messages.get(0).hasNonNull("id")) {
            return messages.get(0).path("id").asText();
        }
        return null;
    }

    public void performCallAction(
            String phoneNumberId,
            String callId,
            String action,
            String sdpType,
            String sdp)
            throws IOException, InterruptedException {
        String effectivePhoneNumberId = hasText(phoneNumberId) ? phoneNumberId : properties.getPhoneNumberId();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("call_id", callId);
        payload.put("action", action);
        if (hasText(sdpType) && hasText(sdp)) {
            payload.put("session", Map.of(
                    "sdp_type", sdpType,
                    "sdp", sdp));
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(graphBaseUrl() + "/" + urlEncode(effectivePhoneNumberId) + "/calls"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn(
                    "WhatsApp call action HTTP failure action={} callId={} phoneNumberId={} status={}",
                    action,
                    callId,
                    effectivePhoneNumberId,
                    response.statusCode());
            throw new IOException("WhatsApp call action failed: " + response.statusCode() + " " + response.body());
        }
        log.info(
                "WhatsApp call action succeeded action={} callId={} phoneNumberId={} status={}",
                action,
                callId,
                effectivePhoneNumberId,
                response.statusCode());
    }

    public CallInitiationResult initiateCall(
            String toPhoneNumber,
            String sdpType,
            String sdp,
            String bizOpaqueCallbackData)
            throws IOException, InterruptedException {
        if (!hasText(sdpType) || !hasText(sdp)) {
            throw new IOException("Outbound WhatsApp call requires an SDP offer");
        }
        String normalizedPhone = normalizeRecipient(toPhoneNumber);
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", normalizedPhone);
        payload.put("action", "connect");
        payload.put("session", Map.of(
                "sdp_type", sdpType,
                "sdp", sdp));
        if (hasText(bizOpaqueCallbackData)) {
            payload.put("biz_opaque_callback_data", bizOpaqueCallbackData);
        }
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(graphBaseUrl() + "/" + urlEncode(properties.getPhoneNumberId()) + "/calls"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + properties.getAccessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn(
                    "WhatsApp outbound call initiation failed to={} phoneNumberId={} status={}",
                    normalizedPhone,
                    properties.getPhoneNumberId(),
                    response.statusCode());
            throw new IOException("WhatsApp outbound call failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        String callId = null;
        JsonNode calls = json.path("calls");
        if (calls.isArray() && !calls.isEmpty()) {
            callId = blankToNull(calls.get(0).path("id").asText());
        }
        if (callId == null) {
            callId = blankToNull(json.path("call_id").asText());
        }
        if (callId == null) {
            throw new IOException("WhatsApp outbound call succeeded but no call id was returned");
        }
        return new CallInitiationResult(callId, properties.getPhoneNumberId(), response.body());
    }

    private String graphBaseUrl() {
        String version = hasText(properties.getGraphApiVersion()) ? properties.getGraphApiVersion().trim() : "v23.0";
        return "https://graph.facebook.com/" + version;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeRecipient(String value) {
        String digitsOnly = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digitsOnly.isBlank() ? value : digitsOnly;
    }

    private static String escapeQuoted(String value) {
        return value == null ? "attachment" : value.replace("\"", "");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 1) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record MediaDescriptor(String mediaId, String downloadUrl, String mimeType) {}

    public record InteractiveListRow(String id, String title, String description) {}

    public record CallInitiationResult(String callId, String phoneNumberId, String rawResponseBody) {}
}
