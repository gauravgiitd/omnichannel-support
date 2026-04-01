package com.omnichannel.support.service;

import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.DocumentLink;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.Task;
import com.omnichannel.support.domain.TaskDocument;
import com.omnichannel.support.repo.DocumentLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentLinkService {

    private final DocumentLinkRepository documentLinkRepository;

    @Transactional
    public void link(TaskDocument document, Conversation conversation, Message message, CustomerJtbd customerJtbd, Task task) {
        if (document == null) {
            return;
        }
        if (conversation != null) {
            saveLink(document, conversation, null, null, null);
        }
        if (message != null) {
            saveLink(document, null, message, null, null);
        }
        if (customerJtbd != null) {
            saveLink(document, null, null, customerJtbd, null);
        }
        if (task != null) {
            saveLink(document, null, null, null, task);
        }
    }

    private void saveLink(TaskDocument document, Conversation conversation, Message message, CustomerJtbd customerJtbd, Task task) {
        boolean exists = documentLinkRepository.findByDocument(document).stream().anyMatch(existing ->
                matches(existing.getConversation(), conversation, Conversation::getId)
                        && matches(existing.getMessage(), message, Message::getId)
                        && matches(existing.getCustomerJtbd(), customerJtbd, CustomerJtbd::getId)
                        && matches(existing.getTask(), task, Task::getId));
        if (exists) {
            return;
        }
        DocumentLink link = new DocumentLink();
        link.setDocument(document);
        link.setConversation(conversation);
        link.setMessage(message);
        link.setCustomerJtbd(customerJtbd);
        link.setTask(task);
        documentLinkRepository.save(link);
    }

    private static <T, I> boolean matches(T left, T right, java.util.function.Function<T, I> idExtractor) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return java.util.Objects.equals(idExtractor.apply(left), idExtractor.apply(right));
    }
}
