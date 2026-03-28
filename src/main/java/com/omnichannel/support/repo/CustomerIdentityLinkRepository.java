package com.omnichannel.support.repo;

import com.omnichannel.support.domain.CustomerIdentityLink;
import com.omnichannel.support.domain.IdentifierType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerIdentityLinkRepository extends JpaRepository<CustomerIdentityLink, Long> {

    Optional<CustomerIdentityLink> findByIdentifierTypeAndIdentifierValue(
            IdentifierType identifierType, String identifierValue);

    List<CustomerIdentityLink> findByCustomerId(String customerId);

    Optional<CustomerIdentityLink> findFirstByCustomerIdAndIdentifierTypeOrderByCreatedAtAsc(
            String customerId, IdentifierType identifierType);
}
