package com.svi.channelconnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ChannelConnectApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChannelConnectApplication.class, args);
    }

}
