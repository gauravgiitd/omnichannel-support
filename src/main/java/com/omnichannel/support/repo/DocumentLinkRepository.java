package com.omnichannel.support.repo;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.DocumentLink;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentLinkRepository extends JpaRepository<DocumentLink, Long> {

    List<DocumentLink> findByDocument(TaskDocument document);

    List<DocumentLink> findByConversation(Conversation conversation);

    List<DocumentLink> findByMessage(Message message);

    List<DocumentLink> findByCustomerJtbd(CustomerJtbd customerJtbd);

    List<DocumentLink> findByTask(Task task);
}
