package com.omnichannel.support.service;

import com.omnichannel.support.config.SupportPlatformProperties;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerChannelNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CustomerChannelNotificationService.class);

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MetaWhatsAppCloudApiClient metaWhatsAppCloudApiClient;
    private final SupportPlatformProperties properties;

    public DirectDeliveryResult send(ChannelType channel, String recipient, String subject, String body) {
        return send(channel, recipient, subject, body, DeliveryOptions.none());
    }

    public DirectDeliveryResult send(
            ChannelType channel, String recipient, String subject, String body, DeliveryOptions options) {
        return switch (channel) {
            case EMAIL -> sendEmail(recipient, subject, body, options);
            case WHATSAPP -> sendWhatsApp(recipient, body);
            case UI -> new DirectDeliveryResult(ChannelType.UI, recipient, null, Map.of("delivery", "app_only"));
        };
    }

    private DirectDeliveryResult sendEmail(String recipient, String subject, String body, DeliveryOptions options) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            throw new ValidationException("mail sender not configured");
        }
        String messageId = buildOutboundMessageId();
        try {
            jakarta.mail.internet.MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, false, StandardCharsets.UTF_8.name());
            helper.setTo(recipient);
            helper.setFrom(properties.outboundEmail().fromAddress(), properties.outboundEmail().fromDisplayName());
            helper.setReplyTo(properties.outboundEmail().fromAddress());
            helper.setSubject(subject);
            helper.setText(body, false);
            mimeMessage.setHeader("Message-ID", messageId);
            if (options.inReplyTo() != null && !options.inReplyTo().isBlank()) {
                mimeMessage.setHeader("In-Reply-To", wrapAngles(stripAngles(options.inReplyTo())));
            }
            List<String> references = new ArrayList<>();
            if (options.references() != null) {
                options.references().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(CustomerChannelNotificationService::stripAngles)
                        .map(CustomerChannelNotificationService::wrapAngles)
                        .forEach(references::add);
            }
            if (options.inReplyTo() != null && !options.inReplyTo().isBlank()) {
                String inReplyTo = wrapAngles(stripAngles(options.inReplyTo()));
                if (!references.contains(inReplyTo)) {
                    references.add(inReplyTo);
                }
            }
            if (!references.isEmpty()) {
                mimeMessage.setHeader("References", String.join(" ", references));
            }
            mailSender.send(mimeMessage);
            return new DirectDeliveryResult(
                    ChannelType.EMAIL,
                    recipient,
                    stripAngles(messageId),
                    Map.of("delivery", "email", "recipient", recipient, "email_subject", subject));
        } catch (Exception ex) {
            throw new ValidationException("failed to send email notification");
        }
    }

    private DirectDeliveryResult sendWhatsApp(String recipient, String body) {
        if (!metaWhatsAppCloudApiClient.canSendMessages()) {
            throw new ValidationException("WhatsApp Cloud API outbound messaging is not configured");
        }
        try {
            String messageId = metaWhatsAppCloudApiClient.sendTextMessage(recipient, body);
            return new DirectDeliveryResult(
                    ChannelType.WHATSAPP,
                    recipient,
                    messageId,
                    Map.of("delivery", "whatsapp", "recipient", recipient));
        } catch (Exception ex) {
            throw new ValidationException("failed to send WhatsApp notification");
        }
    }

    public DirectDeliveryResult sendWhatsAppSelectionList(
            String recipient, String body, String buttonText, java.util.List<MetaWhatsAppCloudApiClient.InteractiveListRow> rows) {
        if (!metaWhatsAppCloudApiClient.canSendMessages()) {
            throw new ValidationException("WhatsApp Cloud API outbound messaging is not configured");
        }
        try {
            String messageId = metaWhatsAppCloudApiClient.sendInteractiveListMessage(recipient, body, buttonText, rows);
            return new DirectDeliveryResult(
                    ChannelType.WHATSAPP,
                    recipient,
                    messageId,
                    Map.of("delivery", "whatsapp", "recipient", recipient, "interactive", true));
        } catch (Exception ex) {
            throw new ValidationException("failed to send WhatsApp notification");
        }
    }

    public DirectDeliveryResult sendWhatsAppTemplate(String recipient, String templateName, String languageCode) {
        if (!metaWhatsAppCloudApiClient.canSendMessages()) {
            throw new ValidationException("WhatsApp Cloud API outbound messaging is not configured");
        }
        try {
            String messageId = metaWhatsAppCloudApiClient.sendTemplateMessage(recipient, templateName, languageCode);
            return new DirectDeliveryResult(
                    ChannelType.WHATSAPP,
                    recipient,
                    messageId,
                    Map.of(
                            "delivery", "whatsapp",
                            "recipient", recipient,
                            "template", templateName,
                            "template_language", languageCode));
        } catch (Exception ex) {
            log.warn(
                    "Failed to send WhatsApp template recipient={} template={} language={}: {}",
                    recipient,
                    templateName,
                    languageCode,
                    ex.getMessage(),
                    ex);
            throw new ValidationException("failed to send WhatsApp template notification: " + ex.getMessage());
        }
    }

    private static String buildOutboundMessageId() {
        return "<notify-" + UUID.randomUUID() + "@support.local>";
    }

    private static String stripAngles(String messageId) {
        if (messageId == null) {
            return null;
        }
        if (messageId.startsWith("<") && messageId.endsWith(">")) {
            return messageId.substring(1, messageId.length() - 1);
        }
        return messageId;
    }

    private static String wrapAngles(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return messageId;
        }
        return "<" + stripAngles(messageId) + ">";
    }

    public record DeliveryOptions(String inReplyTo, List<String> references) {
        public static DeliveryOptions none() {
            return new DeliveryOptions(null, List.of());
        }
    }

    public record DirectDeliveryResult(
            ChannelType channel, String recipient, String externalThreadRef, Map<String, Object> metadata) {}
}
