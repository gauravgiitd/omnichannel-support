package com.omnichannel.support.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WhatsAppNgrokForwardingService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNgrokForwardingService.class);

    private final AdminWhatsAppForwardingConfigService adminWhatsAppForwardingConfigService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public void forwardInboundMessage(JsonNode metadata, JsonNode message) {
        String endpoint = adminWhatsAppForwardingConfigService.ngrokEndpointUrl().orElse(null);
        if (!hasText(endpoint) || message == null || message.isMissingNode() || message.isNull()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("source", "meta_whatsapp_webhook");
            payload.put("received_at", Instant.now().toString());
            payload.put("metadata", metadata);
            payload.put("message", message);

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Omnichannel-Webhook", "whatsapp")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
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
