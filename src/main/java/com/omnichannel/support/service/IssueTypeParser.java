package com.omnichannel.support.service;

import java.util.Locale;

/**
 * Lightweight keyword routing for inbound email when no structured issue_type is supplied.
 * Replace or augment with NLP / classifier integration as needed.
 */
public final class IssueTypeParser {

    private IssueTypeParser() {}

    public static String fromEmail(String subject, String body) {
        String text =
                ((subject != null ? subject : "") + " " + (body != null ? body : ""))
                        .toLowerCase(Locale.ROOT);
        if (text.contains("claim")) {
            return "claims";
        }
        if (text.contains("policy")) {
            return "policy";
        }
        if (text.contains("premium") || text.contains("billing") || text.contains("payment")) {
            return "billing";
        }
        if (text.contains("cancel")) {
            return "cancellation";
        }
        return "email_inbound";
    }
}
