package com.tioka.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.tioka")
public class TiokaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TiokaServerApplication.class, args);
    }
}
