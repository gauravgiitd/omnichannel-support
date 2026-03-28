package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.InboundEmailRequest;
import com.omnichannel.support.dto.InboundWhatsAppRequest;
import com.omnichannel.support.service.InboundEmailService;
import com.omnichannel.support.service.InboundWhatsAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inbound")
@RequiredArgsConstructor
public class InboundChannelController {

    private final InboundEmailService inboundEmailService;
    private final InboundWhatsAppService inboundWhatsAppService;

    @PostMapping(path = "/email", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<InboundEmailService.InboundEmailResult>> inboundEmail(
            @Valid @RequestBody InboundEmailRequest request) {
        InboundEmailService.InboundEmailResult result = inboundEmailService.ingest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @PostMapping(path = "/whatsapp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<InboundWhatsAppService.InboundWhatsAppResult>> inboundWhatsApp(
            @Valid @RequestBody InboundWhatsAppRequest request) {
        InboundWhatsAppService.InboundWhatsAppResult result = inboundWhatsAppService.ingest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }
}
