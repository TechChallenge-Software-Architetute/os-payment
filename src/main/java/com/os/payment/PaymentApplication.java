package com.os.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PaymentApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PaymentApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        application.run(args);
    }
}
