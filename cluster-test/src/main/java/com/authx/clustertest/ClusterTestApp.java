package com.authx.clustertest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClusterTestApp {
    public static void main(String[] args) {
        SpringApplication.run(ClusterTestApp.class, args);
    }
}
