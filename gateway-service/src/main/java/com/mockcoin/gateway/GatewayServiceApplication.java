package com.mockcoin.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// gateway-service 부트 진입점 (REST 라우팅 + WebSocket 게이트웨이 + admin facade)
@SpringBootApplication
@EnableScheduling
public class GatewayServiceApplication {
    // Spring Boot 애플리케이션을 기동한다
    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
