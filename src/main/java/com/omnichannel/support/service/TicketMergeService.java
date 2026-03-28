package com.omnichannel.support.service;

import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketMergeMap;
import com.omnichannel.support.domain.TicketStatus;
import com.omnichannel.support.error.NotFoundException;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.MessageRepository;
import com.omnichannel.support.repo.TicketMergeMapRepository;
import com.omnichannel.support.repo.TicketRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketMergeService {

    private final TicketRepository ticketRepository;
    private final MessageRepository messageRepository;
    private final TicketMergeMapRepository mergeMapRepository;
    private final TicketResolutionService ticketResolutionService;
    private final AuditService auditService;

    @Transactional
    public void mergeTickets(String primaryNumber, String mergedNumber, String mergedByActor) {
        Ticket primary =
                ticketRepository
                        .findByTicketNumber(primaryNumber)
                        .map(ticketResolutionService::resolveCanonical)
                        .orElseThrow(() -> new NotFoundException("primary ticket not found"));
        Ticket merged =
                ticketRepository
                        .findByTicketNumber(mergedNumber)
                        .map(ticketResolutionService::resolveCanonical)
                        .orElseThrow(() -> new NotFoundException("merged ticket not found"));

        if (primary.getId().equals(merged.getId())) {
            throw new ValidationException("cannot merge a ticket into itself");
        }
        if (mergeMapRepository.findByMergedTicket(merged).isPresent()) {
            throw new ValidationException("merged ticket is already merged into another ticket");
        }

        List<Message> messages = messageRepository.findByTicketOrderByCreatedAtAsc(merged);
        for (Message message : messages) {
            message.setTicket(primary);
        }
        messageRepository.saveAll(messages);

        TicketMergeMap map = new TicketMergeMap();
        map.setPrimaryTicket(primary);
        map.setMergedTicket(merged);
        map.setMergedByActor(mergedByActor);
        mergeMapRepository.save(map);

        merged.setStatus(TicketStatus.CLOSED);
        ticketRepository.save(merged);

        auditService.record(
                "TICKET_MERGED",
                "Ticket",
                primary.getTicketNumber(),
                "AGENT",
                mergedByActor,
                java.util.Map.of(
                        "primary_ticket", primary.getTicketNumber(),
                        "merged_ticket", merged.getTicketNumber()));
    }
}
