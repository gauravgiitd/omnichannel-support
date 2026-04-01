package com.omnichannel.support.service;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.Conversation;
import com.omnichannel.support.repo.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerConversationService {

    private final ConversationRepository conversationRepository;

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
}
