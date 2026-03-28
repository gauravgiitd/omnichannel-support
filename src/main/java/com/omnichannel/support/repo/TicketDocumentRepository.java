package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Ticket;
import com.omnichannel.support.domain.TicketDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketDocumentRepository extends JpaRepository<TicketDocument, Long> {

    List<TicketDocument> findByTicketOrderByCreatedAtAsc(Ticket ticket);

    @EntityGraph(attributePaths = "ticket")
    Optional<TicketDocument> findByPublicId(String publicId);
}
