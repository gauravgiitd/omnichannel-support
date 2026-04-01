package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.Task;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationOrderByCreatedAtAsc(Conversation conversation);

    List<Message> findByConversationAndCustomerJtbdOrderByCreatedAtAsc(
            Conversation conversation, CustomerJtbd customerJtbd);

    List<Message> findByTaskOrderByCreatedAtAsc(Task task);

    List<Message> findByTaskIn(List<Task> tasks);

    Optional<Message> findByPublicId(String publicId);

    @EntityGraph(attributePaths = {"task", "conversation", "customerJtbd"})
    Optional<Message> findByExternalThreadRef(String externalThreadRef);
}
