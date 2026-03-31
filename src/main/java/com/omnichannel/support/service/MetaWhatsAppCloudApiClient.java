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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MetaWhatsAppCloudApiClient {

    private static final String GRAPH_BASE_URL = "https://graph.facebook.com/v23.0";

    private final WhatsAppCloudApiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean isConfigured() {
        return hasText(properties.getAccessToken());
    }

    public boolean canSendMessages() {
        return isConfigured() && hasText(properties.getPhoneNumberId());
    }

    public MediaDescriptor getMediaMetadata(String mediaId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        GRAPH_BASE_URL + "/" + urlEncode(mediaId) + "?fields=id,url,mime_type,file_size,sha256"))
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
                        URI.create(GRAPH_BASE_URL + "/" + urlEncode(properties.getPhoneNumberId()) + "/messages"))
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
                        URI.create(GRAPH_BASE_URL + "/" + urlEncode(properties.getPhoneNumberId()) + "/messages"))
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
                        URI.create(GRAPH_BASE_URL + "/" + urlEncode(properties.getPhoneNumberId()) + "/media"))
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
                        URI.create(GRAPH_BASE_URL + "/" + urlEncode(properties.getPhoneNumberId()) + "/messages"))
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

    public record MediaDescriptor(String mediaId, String downloadUrl, String mimeType) {}

    public record InteractiveListRow(String id, String title, String description) {}
}
