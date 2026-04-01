package com.omnichannel.support.service;

import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.MessageJtbdLink;
import com.omnichannel.support.domain.MessageJtbdLinkageType;
import com.omnichannel.support.repo.MessageJtbdLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MessageLinkService {

    private final MessageJtbdLinkRepository messageJtbdLinkRepository;

    @Transactional
    public void linkToJtbd(Message message, CustomerJtbd customerJtbd, MessageJtbdLinkageType linkageType, double confidence) {
        if (message == null || customerJtbd == null) {
            return;
        }
        boolean exists = messageJtbdLinkRepository.findByMessage(message).stream()
                .anyMatch(link -> link.getCustomerJtbd().getId().equals(customerJtbd.getId()));
        if (exists) {
            return;
        }
        MessageJtbdLink link = new MessageJtbdLink();
        link.setMessage(message);
        link.setCustomerJtbd(customerJtbd);
        link.setLinkageType(linkageType != null ? linkageType : MessageJtbdLinkageType.INFERRED);
        link.setConfidence(confidence);
        messageJtbdLinkRepository.save(link);
    }
}
