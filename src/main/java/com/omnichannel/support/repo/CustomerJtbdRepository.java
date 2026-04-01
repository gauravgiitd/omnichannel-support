package com.omnichannel.support.repo;

import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.JtbdInstanceStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerJtbdRepository extends JpaRepository<CustomerJtbd, Long> {

    @EntityGraph(attributePaths = {"jtbdType", "currentStage"})
    Optional<CustomerJtbd> findByPublicId(String publicId);

    @EntityGraph(attributePaths = {"jtbdType", "currentStage"})
    List<CustomerJtbd> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    @EntityGraph(attributePaths = {"jtbdType", "currentStage"})
    List<CustomerJtbd> findByCustomerIdAndStatusOrderByCreatedAtDesc(String customerId, JtbdInstanceStatus status);
}
