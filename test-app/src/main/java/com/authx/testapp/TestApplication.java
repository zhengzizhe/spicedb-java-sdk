package com.authx.testapp;

import com.authx.sdk.AuthxClient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public AuthxClient authxClient(
            @Value("${authx.spicedb.target:localhost:50051}") String target,
            @Value("${authx.spicedb.token:localdevlocaldevlocaldevlocaldev}") String token,
            @Value("${authx.spicedb.tls:false}") boolean tls) {
        return AuthxClient.builder()
                .connection(c -> c
                        .target(target)
                        .presharedKey(token)
                        .tls(tls))
                .build();
    }
}
