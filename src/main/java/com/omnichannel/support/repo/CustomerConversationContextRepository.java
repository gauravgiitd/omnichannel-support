package com.omnichannel.support.repo;

import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.domain.CustomerConversationContext;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerConversationContextRepository extends JpaRepository<CustomerConversationContext, Long> {

    Optional<CustomerConversationContext> findByCustomerIdAndChannel(String customerId, ChannelType channel);

    List<CustomerConversationContext> findByActiveTaskNumber(String activeTaskNumber);
}
