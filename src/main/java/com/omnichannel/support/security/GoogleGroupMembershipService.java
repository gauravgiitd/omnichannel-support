package com.omnichannel.support.security;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.omnichannel.support.config.AuthProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleGroupMembershipService {

    private static final String DIRECTORY_SCOPE =
            "https://www.googleapis.com/auth/admin.directory.group.member.readonly";

    private final AuthProperties authProperties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean isMemberOfAnyConfiguredGroup(String email) {
        List<String> groups = authProperties.getAgent().getAllowedGoogleGroups();
        if (email == null || email.isBlank() || groups == null || groups.isEmpty()) {
            return false;
        }
        if (!isWorkspaceLookupConfigured()) {
            return false;
        }
        for (String groupEmail : groups) {
            if (groupEmail != null && !groupEmail.isBlank() && isMember(groupEmail.trim(), email.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isWorkspaceLookupConfigured() {
        return authProperties.getAgent().getWorkspaceAdminEmail() != null
                && !authProperties.getAgent().getWorkspaceAdminEmail().isBlank()
                && authProperties.getAgent().getServiceAccountJsonBase64() != null
                && !authProperties.getAgent().getServiceAccountJsonBase64().isBlank();
    }

    private boolean isMember(String groupEmail, String memberEmail) {
        try {
            AccessToken token = delegatedCredentials().refreshAccessToken();
            URI uri = URI.create(
                    "https://admin.googleapis.com/admin/directory/v1/groups/"
                            + urlEncode(groupEmail)
                            + "/hasMember/"
                            + urlEncode(memberEmail));
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token.getTokenValue())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200
                    && response.statusCode() < 300
                    && response.body().contains("\"isMember\": true");
        } catch (Exception ignored) {
            return false;
        }
    }

    private GoogleCredentials delegatedCredentials() throws IOException {
        byte[] json = Base64.getDecoder().decode(authProperties.getAgent().getServiceAccountJsonBase64());
        ServiceAccountCredentials credentials = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(json));
        return credentials.createScoped(List.of(DIRECTORY_SCOPE))
                .createDelegated(authProperties.getAgent().getWorkspaceAdminEmail().trim());
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
