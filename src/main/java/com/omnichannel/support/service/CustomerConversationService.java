package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.repo.ConversationRepository;
import com.omnichannel.support.repo.CustomerJtbdRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerConversationService {

    private final ConversationRepository conversationRepository;
    private final CustomerJtbdRepository customerJtbdRepository;

    @Transactional(readOnly = true)
    public Conversation findByCustomerId(String customerId) {
        return conversationRepository.findByCustomerId(customerId).orElse(null);
    }

    @Transactional
    public Conversation getOrCreate(String customerId, ChannelType preferredChannel) {
        return conversationRepository.findByCustomerId(customerId).orElseGet(() -> {
            Conversation conversation = new Conversation();
            conversation.setCustomerId(customerId);
            conversation.setPrimaryChannel(preferredChannel != null ? preferredChannel : ChannelType.UI);
            return conversationRepository.save(conversation);
        });
    }

    @Transactional(readOnly = true)
    public CustomerJtbd activeCustomerJtbd(Conversation conversation) {
        if (conversation == null || conversation.getActiveCustomerJtbdPublicId() == null
                || conversation.getActiveCustomerJtbdPublicId().isBlank()) {
            return null;
        }
        return customerJtbdRepository.findByPublicId(conversation.getActiveCustomerJtbdPublicId()).orElse(null);
    }

    @Transactional
    public Conversation setActiveCustomerJtbd(Conversation conversation, CustomerJtbd customerJtbd) {
        conversation.setActiveCustomerJtbdPublicId(customerJtbd != null ? customerJtbd.getPublicId() : null);
        return conversationRepository.save(conversation);
    }

    @Transactional
    public Conversation clearActiveCustomerJtbd(Conversation conversation) {
        conversation.setActiveCustomerJtbdPublicId(null);
        return conversationRepository.save(conversation);
    }
}
