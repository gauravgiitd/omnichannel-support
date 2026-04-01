package com.omnichannel.support.service;

import com.omnichannel.support.domain.CustomerJtbd;
import com.omnichannel.support.domain.JtbdType;
import com.omnichannel.support.domain.JtbdTypeStage;
import com.omnichannel.support.repo.JtbdTypeRepository;
import com.omnichannel.support.repo.JtbdTypeStageRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InboundMessageUnderstandingService {

    private final JtbdTypeRepository jtbdTypeRepository;
    private final JtbdTypeStageRepository jtbdTypeStageRepository;

    public InboundDecision analyze(
            String body,
            String claimIdHint,
            String policyIdHint,
            boolean hasDocuments,
            List<CustomerJtbd> activeJtbds) {
        String normalized = normalize(body);
        Domain domain = inferDomain(normalized, claimIdHint, policyIdHint);
        CustomerJtbd matchedJtbd = matchJtbd(normalized, activeJtbds, domain);

        if (isPolicyNameUpdate(normalized, hasDocuments)) {
            return new InboundDecision(
                    domain == Domain.UNKNOWN ? Domain.POLICY : domain,
                    matchedJtbd,
                    true,
                    "Policy Name Update",
                    true,
                    "policy_name_update_verification",
                    "queue-policy-expert");
        }
        if (normalized.contains("refund")) {
            return new InboundDecision(
                    domain,
                    matchedJtbd,
                    matchedJtbd == null,
                    "Refund Request",
                    true,
                    "refund_processing",
                    "queue-billing-expert");
        }
        if (normalized.contains("report a claim") || normalized.contains("start a claim")) {
            return new InboundDecision(
                    domain == Domain.UNKNOWN ? Domain.CAR : domain,
                    matchedJtbd,
                    matchedJtbd == null,
                    domain == Domain.HEALTH ? "Health Claim Resolution" : "Motor Claim Resolution",
                    true,
                    "claim_intake",
                    "queue-claims-expert");
        }
        if (matchedJtbd != null && requiresExpertAction(normalized, matchedJtbd, hasDocuments)) {
            return new InboundDecision(
                    domain,
                    matchedJtbd,
                    false,
                    null,
                    true,
                    expertIssueType(matchedJtbd, normalized),
                    expertQueueFor(domain, matchedJtbd));
        }
        return new InboundDecision(
                domain,
                matchedJtbd,
                false,
                null,
                false,
                null,
                generalQueueFor(domain));
    }

    public CustomerJtbd createCustomerJtbd(String customerId, String typeName, JtbdService jtbdService) {
        return jtbdService.findOrCreateActiveJtbdByTypeName(customerId, resolveTypeName(typeName));
    }

    private String resolveTypeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "General support request";
        }
        return jtbdTypeRepository.findAll().stream()
                .map(JtbdType::getName)
                .filter(name -> name.equalsIgnoreCase(raw))
                .findFirst()
                .orElse(raw);
    }

    private CustomerJtbd matchJtbd(String normalized, List<CustomerJtbd> activeJtbds, Domain domain) {
        return activeJtbds.stream()
                .map(jtbd -> new ScoredJtbd(jtbd, score(normalized, jtbd, domain)))
                .filter(scored -> scored.score > 0)
                .max(Comparator.comparingInt(ScoredJtbd::score))
                .map(ScoredJtbd::jtbd)
                .orElse(null);
    }

    private int score(String normalized, CustomerJtbd jtbd, Domain domain) {
        String jtbdText = normalize(jtbd.getJtbdType().getName() + " " + jtbd.getCurrentStage().getStageName());
        int score = 0;
        if (normalized.contains("claim") && jtbdText.contains("claim")) {
            score += 3;
        }
        if ((normalized.contains("car") || normalized.contains("motor") || normalized.contains("garage") || normalized.contains("repair"))
                && (jtbdText.contains("motor") || jtbdText.contains("car") || jtbdText.contains("claim"))) {
            score += 3;
        }
        if (normalized.contains("health") && jtbdText.contains("health")) {
            score += 3;
        }
        if (normalized.contains("policy") && jtbdText.contains("policy")) {
            score += 3;
        }
        if (normalized.contains("refund") && jtbdText.contains("refund")) {
            score += 3;
        }
        if (normalized.contains("status")) {
            score += 1;
        }
        if (domain == Domain.CAR && (jtbdText.contains("motor") || jtbdText.contains("car"))) {
            score += 1;
        }
        if (domain == Domain.HEALTH && jtbdText.contains("health")) {
            score += 1;
        }
        if (domain == Domain.POLICY && jtbdText.contains("policy")) {
            score += 1;
        }
        return score;
    }

    private static boolean isPolicyNameUpdate(String normalized, boolean hasDocuments) {
        return normalized.contains("change the name")
                || normalized.contains("update the name")
                || normalized.contains("name in my policy")
                || normalized.contains("got married")
                || normalized.contains("marriage certificate")
                || (hasDocuments && normalized.contains("name") && normalized.contains("policy"));
    }

    private static boolean requiresExpertAction(String normalized, CustomerJtbd jtbd, boolean hasDocuments) {
        String jtbdText = normalize(jtbd.getJtbdType().getName());
        if (normalized.contains("status")) {
            return false;
        }
        if (jtbdText.contains("claim")) {
            return normalized.contains("repair later")
                    || normalized.contains("get the repair done later")
                    || normalized.contains("drop the car back")
                    || normalized.contains("dropped back")
                    || normalized.contains("return the car")
                    || normalized.contains("garage")
                    || normalized.contains("repair");
        }
        if (jtbdText.contains("policy")) {
            return normalized.contains("change")
                    || normalized.contains("update")
                    || hasDocuments;
        }
        return hasDocuments || normalized.contains("change") || normalized.contains("update");
    }

    private static String expertIssueType(CustomerJtbd jtbd, String normalized) {
        String jtbdText = normalize(jtbd.getJtbdType().getName());
        if (jtbdText.contains("claim")) {
            return "claim_change_request";
        }
        if (jtbdText.contains("policy") && normalized.contains("name")) {
            return "policy_name_update_verification";
        }
        if (jtbdText.contains("policy")) {
            return "policy_servicing";
        }
        return "expert_follow_up";
    }

    private static String expertQueueFor(Domain domain, CustomerJtbd jtbd) {
        String jtbdText = normalize(jtbd.getJtbdType().getName());
        if (jtbdText.contains("claim")) {
            return "queue-claims-expert";
        }
        if (jtbdText.contains("policy")) {
            return "queue-policy-expert";
        }
        return switch (domain) {
            case HEALTH -> "queue-health-expert";
            case CAR -> "queue-car-expert";
            case POLICY -> "queue-policy-expert";
            case BILLING -> "queue-billing-expert";
            case TRAVEL -> "queue-travel-expert";
            case UNKNOWN -> "queue-triage";
        };
    }

    private static String generalQueueFor(Domain domain) {
        return switch (domain) {
            case HEALTH -> "queue-health-support";
            case CAR -> "queue-car-support";
            case POLICY -> "queue-policy-support";
            case BILLING -> "queue-billing-support";
            case TRAVEL -> "queue-travel-support";
            case UNKNOWN -> "queue-triage";
        };
    }

    private static Domain inferDomain(String normalized, String claimIdHint, String policyIdHint) {
        if (normalized.contains("health")) {
            return Domain.HEALTH;
        }
        if (normalized.contains("travel")) {
            return Domain.TRAVEL;
        }
        if (normalized.contains("billing")
                || normalized.contains("premium")
                || normalized.contains("payment")
                || normalized.contains("refund")) {
            return Domain.BILLING;
        }
        if (normalized.contains("policy")) {
            return Domain.POLICY;
        }
        if (normalized.contains("car")
                || normalized.contains("motor")
                || normalized.contains("claim")
                || normalized.contains("garage")
                || normalized.contains("repair")
                || (claimIdHint != null && !claimIdHint.isBlank())) {
            return Domain.CAR;
        }
        if (policyIdHint != null && !policyIdHint.isBlank()) {
            return Domain.POLICY;
        }
        return Domain.UNKNOWN;
    }

    private static String normalize(String body) {
        return body == null ? "" : body.toLowerCase(Locale.ROOT);
    }

    public enum Domain {
        CAR,
        HEALTH,
        POLICY,
        BILLING,
        TRAVEL,
        UNKNOWN
    }

    public record InboundDecision(
            Domain domain,
            CustomerJtbd matchedJtbd,
            boolean createNewJtbd,
            String newJtbdTypeName,
            boolean createExpertTask,
            String taskIssueType,
            String assignedQueue) {}

    private record ScoredJtbd(CustomerJtbd jtbd, int score) {}
}
