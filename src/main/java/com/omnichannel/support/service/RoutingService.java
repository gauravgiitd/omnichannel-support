package com.omnichannel.support.service;

import com.omnichannel.support.config.RoutingProperties;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RoutingService {

    private final RoutingProperties routingProperties;

    /**
     * Resolves target queue from issue type + LoB. Rules are evaluated in order; first match wins.
     * Unmatched combinations go to the triage queue (iterative refinement via ticket PATCH).
     */
    public String resolveQueue(RoutingContext ctx) {
        String issue = normalize(ctx.issueType());
        String lob = normalize(ctx.lob());

        for (RoutingProperties.RoutingRule rule : routingProperties.rules()) {
            if (rule.issueType() == null
                    || rule.issueType().isBlank()
                    || rule.lob() == null
                    || rule.lob().isBlank()
                    || rule.queue() == null
                    || rule.queue().isBlank()) {
                continue;
            }
            if (matches(rule.issueType(), issue) && matches(rule.lob(), lob)) {
                return rule.queue();
            }
        }
        return routingProperties.triageQueue();
    }

    private static boolean matches(String pattern, String value) {
        String p = pattern.trim();
        if ("*".equals(p)) {
            return true;
        }
        return p.equalsIgnoreCase(value);
    }

    private static String normalize(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT);
    }
}
