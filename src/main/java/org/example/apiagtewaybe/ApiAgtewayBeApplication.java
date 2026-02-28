package org.example.apiagtewaybe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApiAgtewayBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiAgtewayBeApplication.class, args);
    }

}
