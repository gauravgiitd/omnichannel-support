package com.omnichannel.support;

import com.omnichannel.support.config.RoutingProperties;
import com.omnichannel.support.config.SupportPlatformProperties;
import com.omnichannel.support.config.AuthProperties;
import com.omnichannel.support.config.DriveStorageProperties;
import com.omnichannel.support.config.GmailPollingProperties;
import com.omnichannel.support.config.WhatsAppCloudApiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        SupportPlatformProperties.class,
        RoutingProperties.class,
        AuthProperties.class,
        GmailPollingProperties.class,
        DriveStorageProperties.class,
        WhatsAppCloudApiProperties.class
})
public class SupportPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportPlatformApplication.class, args);
    }
}
