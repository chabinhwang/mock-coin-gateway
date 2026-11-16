package com.mockcoin.shared.service;

import io.swagger.v3.oas.annotations.media.Schema;

// MSA 각 서비스의 기본 포트를 정의하는 enum
@Schema(description = "서비스 기본 포트 식별자")
public enum ServicePort {
    GATEWAY(8080),
    USER(8085),
    TRADING(8082),
    MARKET(8083),
    INGESTOR(8084),
    COMMUNITY(8086);

    private final int port;

    ServicePort(int port) {
        this.port = port;
    }

    // 해당 서비스의 포트 번호를 반환한다
    public int port() {
        return port;
    }
}
