package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketMergeMap;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketMergeMapRepository extends JpaRepository<TicketMergeMap, Long> {

    Optional<TicketMergeMap> findByMergedTicket(Ticket mergedTicket);

    List<TicketMergeMap> findByPrimaryTicketInOrMergedTicketIn(List<Ticket> primaryTickets, List<Ticket> mergedTickets);

    boolean existsByMergedTicket(Ticket mergedTicket);
}
