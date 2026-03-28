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
import java.util.ArrayList;
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

    public String ensureCustomerFolder(String customerId) throws IOException, InterruptedException {
        String rootFolderId = driveStorageProperties.getFolderId();
        String query =
                "mimeType='application/vnd.google-apps.folder' and trashed=false and name="
                        + objectMapper.writeValueAsString(customerId);
        if (hasText(rootFolderId)) {
            query += " and '" + rootFolderId + "' in parents";
        }

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://www.googleapis.com/drive/v3/files?q="
                                + urlEncode(query)
                                + "&fields=files(id,name)&pageSize=1"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Drive folder lookup failed: " + response.statusCode() + " " + response.body());
        }
        JsonNode files = objectMapper.readTree(response.body()).path("files");
        if (files.isArray() && !files.isEmpty()) {
            return files.get(0).path("id").asText();
        }

        StringBuilder metadata = new StringBuilder();
        metadata.append("{\"name\":").append(objectMapper.writeValueAsString(customerId));
        metadata.append(",\"mimeType\":\"application/vnd.google-apps.folder\"");
        if (hasText(rootFolderId)) {
            metadata.append(",\"parents\":[").append(objectMapper.writeValueAsString(rootFolderId)).append("]");
        }
        metadata.append("}");

        HttpRequest createRequest = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/drive/v3/files?fields=id,name"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(metadata.toString()))
                .build();
        HttpResponse<String> createResponse = httpClient.send(createRequest, HttpResponse.BodyHandlers.ofString());
        if (createResponse.statusCode() < 200 || createResponse.statusCode() >= 300) {
            throw new IOException("Drive folder creation failed: " + createResponse.statusCode() + " " + createResponse.body());
        }
        return objectMapper.readTree(createResponse.body()).path("id").asText();
    }

    public void moveToFolder(String fileId, String folderId) throws IOException, InterruptedException {
        List<String> existingParents = listParents(fileId);
        StringBuilder url = new StringBuilder("https://www.googleapis.com/drive/v3/files/")
                .append(urlEncode(fileId))
                .append("?addParents=")
                .append(urlEncode(folderId))
                .append("&fields=id,parents");
        if (!existingParents.isEmpty()) {
            url.append("&removeParents=").append(urlEncode(String.join(",", existingParents)));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Drive move failed: " + response.statusCode() + " " + response.body());
        }
    }

    public void deleteFile(String fileId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://www.googleapis.com/drive/v3/files/" + urlEncode(fileId)))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken())
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 204 && response.statusCode() != 404) {
            throw new IOException("Drive delete failed: " + response.statusCode() + " " + response.body());
        }
    }

    private List<String> listParents(String fileId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://www.googleapis.com/drive/v3/files/" + urlEncode(fileId) + "?fields=parents"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Drive parent lookup failed: " + response.statusCode() + " " + response.body());
        }
        List<String> parents = new ArrayList<>();
        for (JsonNode parent : objectMapper.readTree(response.body()).path("parents")) {
            parents.add(parent.asText());
        }
        return parents;
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
