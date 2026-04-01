package com.omnichannel.support.api;

import com.omnichannel.support.domain.SenderType;
import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.CreateAuthenticatedTaskRequest;
import com.omnichannel.support.dto.CustomerRequestDto;
import com.omnichannel.support.dto.DocumentDto;
import com.omnichannel.support.dto.MessageDto;
import com.omnichannel.support.dto.PostMessageRequest;
import com.omnichannel.support.dto.RegisterDocumentRequest;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import com.omnichannel.support.service.CustomerRequestService;
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
@RequestMapping("/v1/customers/me/requests")
@RequiredArgsConstructor
public class CustomerRequestController {

    private final CustomerRequestService customerRequestService;
    private final AuthenticatedUserService authenticatedUserService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerRequestDto>>> listMyRequests(Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(customerRequestService.listRequestsForCustomer(user.customerId())));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ApiResponse<CustomerRequestDto>> getRequest(
            @PathVariable("requestId") String requestId, Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(customerRequestService.getRequestForCustomer(user.customerId(), requestId)));
    }

    @GetMapping("/{requestId}/messages")
    public ResponseEntity<ApiResponse<List<MessageDto>>> listMessages(
            @PathVariable("requestId") String requestId, Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(customerRequestService.listMessages(user.customerId(), requestId)));
    }

    @GetMapping("/{requestId}/documents")
    public ResponseEntity<ApiResponse<List<DocumentDto>>> listDocuments(
            @PathVariable("requestId") String requestId, Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(customerRequestService.listDocuments(user.customerId(), requestId)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<CustomerRequestDto>> createRequest(
            @Valid @RequestBody CreateAuthenticatedTaskRequest request, Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        CustomerRequestDto created = customerRequestService.createRequest(user.customerId(), user.email(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @PostMapping(path = "/{requestId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MessageDto>> postMessage(
            @PathVariable("requestId") String requestId,
            @Valid @RequestBody PostMessageRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        MessageDto created = customerRequestService.postMessage(
                user.customerId(),
                user.email(),
                requestId,
                new PostMessageRequest(
                        request.channel(),
                        SenderType.CUSTOMER,
                        user.email(),
                        request.body(),
                        request.attachmentUrls(),
                        request.externalThreadRef(),
                        request.metadata()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }

    @PostMapping(path = "/{requestId}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<DocumentDto>> registerDocument(
            @PathVariable("requestId") String requestId,
            @Valid @RequestBody RegisterDocumentRequest request,
            Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        DocumentDto created = customerRequestService.registerDocument(
                user.customerId(),
                user.email(),
                requestId,
                new RegisterDocumentRequest(
                        request.channel(),
                        SenderType.CUSTOMER,
                        user.email(),
                        request.fileUrl(),
                        request.documentType(),
                        request.claimId(),
                        request.policyId(),
                        request.messageBody(),
                        request.metadata()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
    }
}
