package com.omnichannel.support.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support.whatsapp-cloud-api")
public class WhatsAppCloudApiProperties {

    private boolean enabled;
    private boolean callingEnabled;
    private String verifyToken;
    private String accessToken;
    private String phoneNumberId;
    private String graphApiVersion = "v23.0";
    private String callPermissionTemplateName;
    private String callPermissionTemplateLanguage = "en";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCallingEnabled() {
        return callingEnabled;
    }

    public void setCallingEnabled(boolean callingEnabled) {
        this.callingEnabled = callingEnabled;
    }

    public String getVerifyToken() {
        return verifyToken;
    }

    public void setVerifyToken(String verifyToken) {
        this.verifyToken = verifyToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    public void setPhoneNumberId(String phoneNumberId) {
        this.phoneNumberId = phoneNumberId;
    }

    public String getGraphApiVersion() {
        return graphApiVersion;
    }

    public void setGraphApiVersion(String graphApiVersion) {
        this.graphApiVersion = graphApiVersion;
    }

    public String getCallPermissionTemplateName() {
        return callPermissionTemplateName;
    }

    public void setCallPermissionTemplateName(String callPermissionTemplateName) {
        this.callPermissionTemplateName = callPermissionTemplateName;
    }

    public String getCallPermissionTemplateLanguage() {
        return callPermissionTemplateLanguage;
    }

    public void setCallPermissionTemplateLanguage(String callPermissionTemplateLanguage) {
        this.callPermissionTemplateLanguage = callPermissionTemplateLanguage;
    }
}
