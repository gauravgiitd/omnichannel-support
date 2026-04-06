package com.omnichannel.support.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.omnichannel.support.config.GmailPollingProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GmailApiClient {

    private static final String GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.modify";
    private static final Logger log = LoggerFactory.getLogger(GmailApiClient.class);

    private final GmailPollingProperties properties;
    private final ObjectMapper objectMapper;

    public boolean isConfigured() {
        return hasText(properties.getClientId())
                && hasText(properties.getClientSecret())
                && hasText(properties.getRefreshToken());
    }

    public List<String> listUnreadInboxMessageIds(String query, int maxResults) throws IOException, InterruptedException {
        JsonNode response = getJson("/gmail/v1/users/" + userId() + "/messages?q="
                + urlEncode(query)
                + "&maxResults="
                + maxResults);
        JsonNode messages = response.path("messages");
        if (!messages.isArray()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode message : messages) {
            if (message.hasNonNull("id")) {
                ids.add(message.get("id").asText());
            }
        }
        return ids;
    }

    public JsonNode getMessage(String messageId) throws IOException, InterruptedException {
        return getJson("/gmail/v1/users/" + userId() + "/messages/" + urlEncode(messageId) + "?format=full");
    }

    public byte[] getAttachment(String messageId, String attachmentId) throws IOException, InterruptedException {
        JsonNode response = getJson("/gmail/v1/users/" + userId() + "/messages/" + urlEncode(messageId)
                + "/attachments/" + urlEncode(attachmentId));
        String data = response.path("data").asText("");
        return data.isBlank() ? new byte[0] : Base64.getUrlDecoder().decode(data);
    }

    public void markProcessed(String messageId) throws IOException, InterruptedException {
        postJson("/gmail/v1/users/" + userId() + "/messages/" + urlEncode(messageId) + "/modify",
                Map.of("removeLabelIds", List.of("UNREAD")));
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com" + path))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .header("Authorization", "Bearer " + accessToken())
                .GET()
                .build();
        HttpResponse<String> response = sendWithRetry(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Gmail API GET failed: " + response.statusCode() + " " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private void postJson(String path, Map<String, Object> payload) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://gmail.googleapis.com" + path))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = sendWithRetry(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Gmail API POST failed: " + response.statusCode() + " " + response.body());
        }
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        int maxAttempts = Math.max(1, properties.getRequestRetries() + 1);
        HttpTimeoutException timeout = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt += 1) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException ex) {
                timeout = ex;
                if (attempt >= maxAttempts) {
                    break;
                }
                log.warn("Gmail API request timed out on attempt {} of {}; retrying once more", attempt, maxAttempts);
            }
        }
        throw timeout != null ? timeout : new HttpTimeoutException("gmail request timed out");
    }

    private String accessToken() throws IOException {
        GoogleCredentials credentials = UserCredentials.newBuilder()
                .setClientId(properties.getClientId())
                .setClientSecret(properties.getClientSecret())
                .setRefreshToken(properties.getRefreshToken())
                .build()
                .createScoped(List.of(GMAIL_SCOPE));
        AccessToken token = credentials.refreshAccessToken();
        return token.getTokenValue();
    }

    private String userId() {
        return urlEncode(properties.getUserId() == null ? "me" : properties.getUserId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
