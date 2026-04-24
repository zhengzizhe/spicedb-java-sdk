package com.authx.testapp;

import com.authx.sdk.AuthxClient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public AuthxClient authxClient() {
        return AuthxClient.inMemory();
    }
}
