package com.example.kafkafailover;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KafkaFailoverApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaFailoverApplication.class, args);
    }
}
