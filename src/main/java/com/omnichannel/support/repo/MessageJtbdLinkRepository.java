package com.omnichannel.support.repo;

import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.Message;
import com.omnichannel.support.domain.MessageJtbdLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageJtbdLinkRepository extends JpaRepository<MessageJtbdLink, Long> {

    List<MessageJtbdLink> findByMessage(Message message);

    List<MessageJtbdLink> findByCustomerJtbd(CustomerJtbd customerJtbd);
}
