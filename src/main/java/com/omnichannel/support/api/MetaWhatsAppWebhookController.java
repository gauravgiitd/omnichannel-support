package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.service.MetaWhatsAppWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks/meta/whatsapp")
@RequiredArgsConstructor
public class MetaWhatsAppWebhookController {

    private final MetaWhatsAppWebhookService metaWhatsAppWebhookService;

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (!metaWhatsAppWebhookService.isConfigured()) {
            return ResponseEntity.notFound().build();
        }
        if ("subscribe".equals(mode) && metaWhatsAppWebhookService.isValidVerifyToken(verifyToken) && challenge != null) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("forbidden");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MetaWhatsAppWebhookService.MetaWebhookResult>> receive(
            @RequestBody String payload) throws Exception {
        if (!metaWhatsAppWebhookService.isConfigured()) {
            throw new ValidationException("Meta WhatsApp Cloud API is not configured");
        }
        MetaWhatsAppWebhookService.MetaWebhookResult result = metaWhatsAppWebhookService.process(payload);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
