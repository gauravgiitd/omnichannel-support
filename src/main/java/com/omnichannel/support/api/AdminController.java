package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.CustomerContactMappingDto;
import com.omnichannel.support.dto.UpsertCustomerContactMappingRequest;
import com.omnichannel.support.service.AdminCleanupService;
import com.omnichannel.support.service.CustomerContactMappingService;
import com.omnichannel.support.service.TicketOriginReplyService;
import com.omnichannel.support.service.TicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminCleanupService adminCleanupService;
    private final CustomerContactMappingService customerContactMappingService;
    private final TicketService ticketService;
    private final TicketOriginReplyService ticketOriginReplyService;

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<AdminCleanupService.AdminSummary>> summary() {
        return ResponseEntity.ok(ApiResponse.success(adminCleanupService.summary()));
    }

    @GetMapping("/contact-mappings")
    public ResponseEntity<ApiResponse<List<CustomerContactMappingDto>>> contactMappings() {
        return ResponseEntity.ok(ApiResponse.success(customerContactMappingService.listAll()));
    }

    @GetMapping("/tickets/{ticketId}/delivery-debug")
    public ResponseEntity<ApiResponse<TicketOriginReplyService.DeliveryDebug>> ticketDeliveryDebug(
            @PathVariable("ticketId") String ticketId) {
        return ResponseEntity.ok(ApiResponse.success(
                ticketOriginReplyService.debugTicketRouting(ticketService.loadCanonicalTicket(ticketId))));
    }

    @PostMapping("/contact-mappings")
    public ResponseEntity<ApiResponse<CustomerContactMappingDto>> createContactMapping(
            @Valid @RequestBody UpsertCustomerContactMappingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(customerContactMappingService.create(request)));
    }

    @PutMapping("/contact-mappings/{id}")
    public ResponseEntity<ApiResponse<CustomerContactMappingDto>> updateContactMapping(
            @PathVariable("id") Long id, @Valid @RequestBody UpsertCustomerContactMappingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(customerContactMappingService.update(id, request)));
    }

    @DeleteMapping("/contact-mappings/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteContactMapping(@PathVariable("id") Long id) {
        customerContactMappingService.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/cleanup")
    public ResponseEntity<ApiResponse<AdminCleanupService.CleanupResult>> cleanup(
            @Valid @RequestBody CleanupRequest request) {
        if (!"DELETE ALL DATA".equals(request.confirmation())) {
            throw new com.omnichannel.support.error.ValidationException("confirmation text does not match");
        }
        return ResponseEntity.ok(ApiResponse.success(adminCleanupService.cleanupAllData()));
    }

    @PostMapping("/cleanup/customer")
    public ResponseEntity<ApiResponse<AdminCleanupService.CustomerCleanupResult>> cleanupCustomer(
            @Valid @RequestBody CustomerCleanupRequest request) {
        if (!"DELETE CUSTOMER DATA".equals(request.confirmation())) {
            throw new com.omnichannel.support.error.ValidationException("confirmation text does not match");
        }
        return ResponseEntity.ok(ApiResponse.success(adminCleanupService.cleanupCustomerData(request.customerId())));
    }

    public record CleanupRequest(@NotBlank String confirmation) {}

    public record CustomerCleanupRequest(@NotBlank String customerId, @NotBlank String confirmation) {}
}
