package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import com.omnichannel.support.security.TicketAccessService;
import com.omnichannel.support.service.TicketService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tickets")
@RequiredArgsConstructor
public class DocumentController {

    private final TicketService ticketService;
    private final TicketAccessService ticketAccessService;
    private final AuthenticatedUserService authenticatedUserService;

    @GetMapping("/{ticketId}/documents")
    public ResponseEntity<ApiResponse<List<DocumentDto>>> listDocuments(
            @PathVariable("ticketId") String ticketId, Authentication authentication) {
        ticketAccessService.assertCanAccessTicket(authentication, ticketId);
        return ResponseEntity.ok(ApiResponse.success(ticketService.listDocuments(ticketId)));
    }

    @PostMapping(path = "/{ticketId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DocumentDto>> registerDocument(
            @PathVariable("ticketId") String ticketId,
            @Valid @RequestBody RegisterDocumentRequest request,
            Authentication authentication) {
        ticketAccessService.assertCanAccessTicket(authentication, ticketId);
        RegisterDocumentRequest trustedRequest = request;
        if (!authenticatedUserService.isAgent(authentication)) {
            AppUser user = authenticatedUserService.requireCurrentUser(authentication);
            trustedRequest = new RegisterDocumentRequest(
                    request.channel(),
                    com.omnichannel.support.domain.SenderType.CUSTOMER,
                    user.email(),
                    request.fileUrl(),
                    request.documentType(),
                    request.claimId(),
                    request.policyId(),
                    request.messageBody(),
                    request.metadata());
        }
        DocumentDto created = ticketService.registerDocument(ticketId, trustedRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }
}
