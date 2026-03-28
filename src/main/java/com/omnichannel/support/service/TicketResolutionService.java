package com.omnichannel.support.service;

import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketMergeMap;
import com.omnichannel.support.repo.TicketMergeMapRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketResolutionService {

    private final TicketMergeMapRepository mergeMapRepository;

    public Ticket resolveCanonical(Ticket ticket) {
        Ticket current = ticket;
        while (true) {
            java.util.Optional<TicketMergeMap> merge = mergeMapRepository.findByMergedTicket(current);
            if (merge.isEmpty()) {
                return current;
            }
            current = merge.get().getPrimaryTicket();
        }
    }
}
