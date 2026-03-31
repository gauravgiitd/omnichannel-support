package com.omnichannel.support.service;

import com.omnichannel.support.config.SupportPlatformProperties;
import com.omnichannel.support.domain.ChannelType;
import com.omnichannel.support.error.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerChannelNotificationService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final MetaWhatsAppCloudApiClient metaWhatsAppCloudApiClient;
    private final SupportPlatformProperties properties;

    public DirectDeliveryResult send(ChannelType channel, String recipient, String subject, String body) {
        return switch (channel) {
            case EMAIL -> sendEmail(recipient, subject, body);
            case WHATSAPP -> sendWhatsApp(recipient, body);
            case UI -> new DirectDeliveryResult(ChannelType.UI, recipient, null, Map.of("delivery", "app_only"));
        };
    }

    private DirectDeliveryResult sendEmail(String recipient, String subject, String body) {
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

    public record DirectDeliveryResult(
            ChannelType channel, String recipient, String externalThreadRef, Map<String, Object> metadata) {}
}
