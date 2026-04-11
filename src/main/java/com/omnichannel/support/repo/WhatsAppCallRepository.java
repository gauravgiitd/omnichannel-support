package com.omnichannel.support.repo;

import com.omnichannel.support.domain.WhatsAppCall;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WhatsAppCallRepository extends JpaRepository<WhatsAppCall, Long> {

    Optional<WhatsAppCall> findByCallId(String callId);

    List<WhatsAppCall> findTop10ByCustomerIdOrderByUpdatedAtDesc(String customerId);

    List<WhatsAppCall> findByCustomerId(String customerId);

    List<WhatsAppCall> findByPhoneNumberOrderByUpdatedAtDesc(String phoneNumber);

    List<WhatsAppCall> findTop50ByOrderByUpdatedAtDesc();
}
