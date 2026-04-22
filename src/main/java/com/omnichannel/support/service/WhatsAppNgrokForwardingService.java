package com.omnichannel.support.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WhatsAppNgrokForwardingService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNgrokForwardingService.class);

    private final AdminWhatsAppForwardingConfigService adminWhatsAppForwardingConfigService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * Forwards the Meta webhook request body unchanged (same JSON as received from Meta).
     */
    public void forwardRawPayload(String rawPayload) {
        String endpoint = adminWhatsAppForwardingConfigService.ngrokEndpointUrl().orElse(null);
        if (!hasText(endpoint) || !hasText(rawPayload)) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Omnichannel-Webhook", "whatsapp")
                    .POST(HttpRequest.BodyPublishers.ofString(rawPayload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Ngrok forwarding failed status={} endpoint={}", response.statusCode(), endpoint);
            }
        } catch (Exception ex) {
            log.warn("Failed forwarding inbound WhatsApp message to ngrok endpoint {}", endpoint, ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
