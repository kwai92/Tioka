package com.tioka.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.tioka")
@EnableScheduling
public class TiokaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TiokaServerApplication.class, args);
    }
}
