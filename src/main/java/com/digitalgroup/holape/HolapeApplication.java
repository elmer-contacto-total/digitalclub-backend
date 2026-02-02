package com.digitalgroup.holape;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
// import org.springframework.scheduling.annotation.EnableAsync;
// import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaAuditing
// @EnableAsync      // DISABLED - Workers desactivados temporalmente
// @EnableScheduling // DISABLED - Workers desactivados temporalmente
public class HolapeApplication {

    public static void main(String[] args) {
        SpringApplication.run(HolapeApplication.class, args);
    }
}
