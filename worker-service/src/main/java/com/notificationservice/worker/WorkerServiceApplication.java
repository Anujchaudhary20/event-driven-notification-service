package com.notificationservice.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.notificationservice")
@EntityScan(basePackages = "com.notificationservice.shared.entity")
@EnableJpaRepositories(basePackages = "com.notificationservice.worker.repository")
public class WorkerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerServiceApplication.class, args);
    }
}
