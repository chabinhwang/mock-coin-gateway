package com.mockcoin.shared.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * 서비스 기본 포트는 로컬 실행 스크립트·k6 BASE_URL·Helm 매니페스트가 함께 쓰는 값이다.
 * 중복되면 로컬 전체 기동이 조용히 실패한다.
 */
class ServicePortTest {

    @Test
    void 서비스별_기본_포트를_고정한다() {
        assertThat(ServicePort.GATEWAY.port()).isEqualTo(8080);
        assertThat(ServicePort.TRADING.port()).isEqualTo(8082);
        assertThat(ServicePort.MARKET.port()).isEqualTo(8083);
        assertThat(ServicePort.INGESTOR.port()).isEqualTo(8084);
        assertThat(ServicePort.USER.port()).isEqualTo(8085);
        assertThat(ServicePort.COMMUNITY.port()).isEqualTo(8086);
    }

    @Test
    void 서비스_간_포트가_중복되지_않는다() {
        assertThat(Arrays.stream(ServicePort.values()).map(ServicePort::port).distinct().count())
                .isEqualTo(ServicePort.values().length);
    }

    @Test
    void MSA_구성_서비스는_6종이다() {
        assertThat(ServicePort.values()).hasSize(6);
    }
}
