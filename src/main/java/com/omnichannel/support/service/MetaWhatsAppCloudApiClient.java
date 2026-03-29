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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record MediaDescriptor(String mediaId, String downloadUrl, String mimeType) {}
}
