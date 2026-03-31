package com.omnichannel.support.repo;

import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.JtbdInstanceStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerJtbdRepository extends JpaRepository<CustomerJtbd, Long> {

    Optional<CustomerJtbd> findByPublicId(String publicId);

    List<CustomerJtbd> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<CustomerJtbd> findByCustomerIdAndStatusOrderByCreatedAtDesc(String customerId, JtbdInstanceStatus status);
}
