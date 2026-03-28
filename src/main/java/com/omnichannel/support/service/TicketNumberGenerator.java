package com.omnichannel.support.service;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TicketNumberGenerator {

    public String newTicketNumber() {
        String compact = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        return "TKT-" + compact;
    }
}
