package com.shilian.wecomsync;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WecomSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(WecomSyncApplication.class, args);
    }
}
