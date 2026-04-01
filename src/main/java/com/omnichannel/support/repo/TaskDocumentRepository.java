package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskDocumentRepository extends JpaRepository<TaskDocument, Long> {

    List<TaskDocument> findByConversationOrderByCreatedAtAsc(Conversation conversation);

    List<TaskDocument> findByConversationAndCustomerJtbdOrderByCreatedAtAsc(
            Conversation conversation, CustomerJtbd customerJtbd);

    List<TaskDocument> findByTaskOrderByCreatedAtAsc(Task task);

    List<TaskDocument> findByTaskIn(List<Task> tasks);

    @EntityGraph(attributePaths = {"task", "conversation", "customerJtbd"})
    Optional<TaskDocument> findByPublicId(String publicId);
}
