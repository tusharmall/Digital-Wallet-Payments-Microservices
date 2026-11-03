package com.example.kyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class KycStatementServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(KycStatementServiceApplication.class, args);
    }
}
