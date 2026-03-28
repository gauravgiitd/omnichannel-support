package com.omnichannel.support.security;

import com.omnichannel.support.domain.Ticket;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketAccessService {

    private final AuthenticatedUserService authenticatedUserService;
    private final com.omnichannel.support.service.TicketService ticketService;

    public void assertCanAccessTicket(Authentication authentication, String ticketNumber) {
        if (authenticatedUserService.isAgent(authentication)) {
            return;
        }
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        Ticket ticket = ticketService.loadCanonicalTicket(ticketNumber);
        if (!ticket.getCustomerId().equals(user.customerId())) {
            throw new AccessDeniedException("You do not have access to this ticket");
        }
    }

    public void assertCanAccessCustomer(Authentication authentication, String customerId) {
        if (authenticatedUserService.isAgent(authentication)) {
            return;
        }
        AppUser user = authenticatedUserService.requireCurrentUser(authentication);
        if (!user.customerId().equals(customerId)) {
            throw new AccessDeniedException("You do not have access to this customer");
        }
    }
}
