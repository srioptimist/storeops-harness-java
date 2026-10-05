package com.storeops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** StoreOps API entry point. Component-scans every module under {@code com.storeops}. */
@SpringBootApplication
public class StoreOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoreOpsApplication.class, args);
    }
}
