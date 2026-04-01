package com.omnichannel.support.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support.auth")
public class AuthProperties {

    private final Agent agent = new Agent();
    private final Expert expert = new Expert();
    private final Admin admin = new Admin();

    public Agent getAgent() {
        return agent;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Expert getExpert() {
        return expert;
    }

    public static class Agent {
        private List<String> allowedEmails = new ArrayList<>();
        private List<String> allowedGoogleGroups = new ArrayList<>();
        private String workspaceAdminEmail;
        private String serviceAccountJsonBase64;

        public List<String> getAllowedEmails() {
            return allowedEmails;
        }

        public void setAllowedEmails(List<String> allowedEmails) {
            this.allowedEmails = allowedEmails;
        }

        public List<String> getAllowedGoogleGroups() {
            return allowedGoogleGroups;
        }

        public void setAllowedGoogleGroups(List<String> allowedGoogleGroups) {
            this.allowedGoogleGroups = allowedGoogleGroups;
        }

        public String getWorkspaceAdminEmail() {
            return workspaceAdminEmail;
        }

        public void setWorkspaceAdminEmail(String workspaceAdminEmail) {
            this.workspaceAdminEmail = workspaceAdminEmail;
        }

        public String getServiceAccountJsonBase64() {
            return serviceAccountJsonBase64;
        }

        public void setServiceAccountJsonBase64(String serviceAccountJsonBase64) {
            this.serviceAccountJsonBase64 = serviceAccountJsonBase64;
        }
    }

    public static class Admin {
        private List<String> allowedEmails = new ArrayList<>();

        public List<String> getAllowedEmails() {
            return allowedEmails;
        }

        public void setAllowedEmails(List<String> allowedEmails) {
            this.allowedEmails = allowedEmails;
        }
    }

    public static class Expert {
        private List<String> allowedEmails = new ArrayList<>();

        public List<String> getAllowedEmails() {
            return allowedEmails;
        }

        public void setAllowedEmails(List<String> allowedEmails) {
            this.allowedEmails = allowedEmails;
        }
    }
}
