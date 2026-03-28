package com.omnichannel.support;

import com.omnichannel.support.config.RoutingProperties;
import com.omnichannel.support.config.SupportPlatformProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({SupportPlatformProperties.class, RoutingProperties.class})
public class SupportPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportPlatformApplication.class, args);
    }
}
