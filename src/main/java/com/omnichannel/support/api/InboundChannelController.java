package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.InboundEmailRequest;
import com.omnichannel.support.dto.InboundWhatsAppRequest;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import com.omnichannel.support.service.InboundEmailService;
import com.omnichannel.support.service.InboundWhatsAppService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
    private final AuthenticatedUserService authenticatedUserService;

    @PostMapping(path = "/email", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<InboundEmailService.InboundEmailResult>> inboundEmail(
            @Valid @RequestBody InboundEmailRequest request, Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        InboundEmailRequest trustedRequest = new InboundEmailRequest(
                user.email(),
                request.toAddress(),
                request.subject(),
                request.bodyText(),
                request.messageId(),
                request.inReplyTo(),
                request.references(),
                user.customerId(),
                request.policyIdHint(),
                request.claimIdHint(),
                request.lobHint(),
                request.forceNewTicket(),
                request.attachments());
        InboundEmailService.InboundEmailResult result = inboundEmailService.ingest(trustedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }

    @PostMapping(path = "/whatsapp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<InboundWhatsAppService.InboundWhatsAppResult>> inboundWhatsApp(
            @Valid @RequestBody InboundWhatsAppRequest request, Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        InboundWhatsAppRequest trustedRequest = new InboundWhatsAppRequest(
                request.waMessageId(),
                request.fromE164Phone(),
                request.bodyText(),
                user.customerId(),
                request.ticketNumberHint(),
                request.issueTypeHint(),
                request.lobHint(),
                request.policyIdHint(),
                request.claimIdHint(),
                request.forceNewTicket(),
                request.attachmentUrls());
        InboundWhatsAppService.InboundWhatsAppResult result = inboundWhatsAppService.ingest(trustedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }
}
