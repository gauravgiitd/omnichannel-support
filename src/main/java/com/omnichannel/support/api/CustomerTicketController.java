package com.omnichannel.support.api;

import com.omnichannel.support.dto.ApiResponse;
import com.omnichannel.support.dto.TicketDto;
import com.omnichannel.support.security.AppUser;
import com.omnichannel.support.security.AuthenticatedUserService;
import com.omnichannel.support.security.TicketAccessService;
import com.omnichannel.support.service.TicketService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/customers")
@RequiredArgsConstructor
public class CustomerTicketController {

    private final TicketService ticketService;
    private final AuthenticatedUserService authenticatedUserService;
    private final TicketAccessService ticketAccessService;

    @GetMapping("/me/tickets")
    public ResponseEntity<ApiResponse<List<TicketDto>>> listMyTickets(Authentication authentication) {
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        return ResponseEntity.ok(ApiResponse.success(ticketService.listTicketsForCustomer(user.customerId())));
    }

    @GetMapping("/{customerId}/tickets")
    public ResponseEntity<ApiResponse<List<TicketDto>>> listTickets(
            @PathVariable("customerId") String customerId, Authentication authentication) {
        ticketAccessService.assertCanAccessCustomer(authentication, customerId);
        return ResponseEntity.ok(ApiResponse.success(ticketService.listTicketsForCustomer(customerId)));
    }
}
