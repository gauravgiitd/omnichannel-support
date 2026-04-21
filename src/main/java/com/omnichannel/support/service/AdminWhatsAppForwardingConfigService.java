package com.omnichannel.support.service;

import com.omnichannel.support.domain.AdminSetting;
import com.omnichannel.support.dto.AdminWhatsAppForwardingConfigDto;
import com.omnichannel.support.error.ValidationException;
import com.omnichannel.support.repo.AdminSettingRepository;
import java.net.URI;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminWhatsAppForwardingConfigService {

    private static final String NGROK_ENDPOINT_KEY = "whatsapp.ngrok.endpoint_url";

    private final AdminSettingRepository adminSettingRepository;

    @Transactional(readOnly = true)
    public AdminWhatsAppForwardingConfigDto getConfig() {
        return adminSettingRepository
                .findById(NGROK_ENDPOINT_KEY)
                .map(setting -> new AdminWhatsAppForwardingConfigDto(setting.getSettingValue(), setting.getUpdatedAt()))
                .orElseGet(() -> new AdminWhatsAppForwardingConfigDto(null, null));
    }

    @Transactional
    public AdminWhatsAppForwardingConfigDto update(String ngrokEndpointUrl) {
        String normalizedEndpoint = normalizeEndpoint(ngrokEndpointUrl);
        if (normalizedEndpoint == null) {
            adminSettingRepository.deleteById(NGROK_ENDPOINT_KEY);
            return new AdminWhatsAppForwardingConfigDto(null, null);
        }
        AdminSetting setting = adminSettingRepository
                .findById(NGROK_ENDPOINT_KEY)
                .orElseGet(() -> {
                    AdminSetting created = new AdminSetting();
                    created.setSettingKey(NGROK_ENDPOINT_KEY);
                    return created;
                });
        setting.setSettingValue(normalizedEndpoint);
        AdminSetting saved = adminSettingRepository.save(setting);
        return new AdminWhatsAppForwardingConfigDto(saved.getSettingValue(), saved.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public Optional<String> ngrokEndpointUrl() {
        return adminSettingRepository
                .findById(NGROK_ENDPOINT_KEY)
                .map(AdminSetting::getSettingValue)
                .filter(this::hasText);
    }

    private String normalizeEndpoint(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("ngrok endpoint must be a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ValidationException("ngrok endpoint must start with http:// or https://");
        }
        if (!hasText(uri.getHost())) {
            throw new ValidationException("ngrok endpoint must include a hostname");
        }
        return uri.toString();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
