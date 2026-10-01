package com.dtc.transit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TransitApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransitApplication.class, args);
    }
}
