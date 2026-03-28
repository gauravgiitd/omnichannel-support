package com.omnichannel.support.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.omnichannel.support.config.DriveStorageProperties;
import com.omnichannel.support.config.GmailPollingProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleDriveStorageService {

    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";

    private final DriveStorageProperties driveStorageProperties;
    private final GmailPollingProperties gmailPollingProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public boolean isConfigured() {
        return hasText(clientId()) && hasText(clientSecret()) && hasText(refreshToken());
    }

    public StoredDriveFile upload(String fileName, String mimeType, byte[] bytes, String description)
            throws IOException, InterruptedException {
        String boundary = "drive-upload-" + System.nanoTime();
        String safeMime = hasText(mimeType) ? mimeType : "application/octet-stream";

        StringBuilder metadata = new StringBuilder();
        metadata.append("{\"name\":").append(objectMapper.writeValueAsString(fileName));
        if (hasText(description)) {
            metadata.append(",\"description\":").append(objectMapper.writeValueAsString(description));
        }
        if (hasText(driveStorageProperties.getFolderId())) {
            metadata.append(",\"parents\":[").append(objectMapper.writeValueAsString(driveStorageProperties.getFolderId())).append("]");
        }
        metadata.append("}");

        byte[] prefix = (
                "--" + boundary + "\r\n"
                        + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                        + metadata
                        + "\r\n--" + boundary + "\r\n"
                        + "Content-Type: " + safeMime + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--").getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[prefix.length + bytes.length + suffix.length];
        System.arraycopy(prefix, 0, payload, 0, prefix.length);
        System.arraycopy(bytes, 0, payload, prefix.length, bytes.length);
        System.arraycopy(suffix, 0, payload, prefix.length + bytes.length, suffix.length);

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "multipart/related; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Drive upload failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode json = objectMapper.readTree(response.body());
        return new StoredDriveFile(
                json.path("id").asText(),
                json.path("name").asText(fileName),
                json.path("mimeType").asText(safeMime));
    }

    public byte[] download(String fileId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://www.googleapis.com/drive/v3/files/" + urlEncode(fileId) + "?alt=media"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken())
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Drive download failed: " + response.statusCode());
        }
        return response.body();
    }

    private String accessToken() throws IOException {
        GoogleCredentials credentials = UserCredentials.newBuilder()
                .setClientId(clientId())
                .setClientSecret(clientSecret())
                .setRefreshToken(refreshToken())
                .build()
                .createScoped(List.of(DRIVE_SCOPE));
        AccessToken token = credentials.refreshAccessToken();
        return token.getTokenValue();
    }

    private String clientId() {
        return hasText(driveStorageProperties.getClientId())
                ? driveStorageProperties.getClientId()
                : gmailPollingProperties.getClientId();
    }

    private String clientSecret() {
        return hasText(driveStorageProperties.getClientSecret())
                ? driveStorageProperties.getClientSecret()
                : gmailPollingProperties.getClientSecret();
    }

    private String refreshToken() {
        return hasText(driveStorageProperties.getRefreshToken())
                ? driveStorageProperties.getRefreshToken()
                : gmailPollingProperties.getRefreshToken();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record StoredDriveFile(String fileId, String fileName, String mimeType) {}
}
