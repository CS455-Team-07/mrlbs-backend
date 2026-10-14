package com.mrlbs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MrlbsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MrlbsApplication.class, args);
    }
}
