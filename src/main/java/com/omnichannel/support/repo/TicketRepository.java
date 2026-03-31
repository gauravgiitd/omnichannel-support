package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    Optional<Ticket> findByTicketNumber(String ticketNumber);

    List<Ticket> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<Ticket> findByCustomerIdAndStatusInOrderByCreatedAtDesc(
            String customerId, Collection<TicketStatus> statuses);

    List<Ticket> findByCustomerJtbdIdAndStatusInOrderByCreatedAtDesc(
            Long customerJtbdId, Collection<TicketStatus> statuses);
}
