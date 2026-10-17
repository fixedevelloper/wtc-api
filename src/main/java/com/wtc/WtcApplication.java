package com.wtc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Point d'entrée de l'API WTC. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class WtcApplication {

    public static void main(String[] args) {
        SpringApplication.run(WtcApplication.class, args);
    }
}
