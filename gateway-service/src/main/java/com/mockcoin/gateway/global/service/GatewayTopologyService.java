package com.mockcoin.gateway.global.service;

import java.util.List;

import com.mockcoin.shared.service.ServiceDescriptor;
import com.mockcoin.shared.service.ServicePort;
import org.springframework.stereotype.Service;

// 각 mock-coin MSA 서비스의 식별자/포트/안정된 baseline 대표 테이블 메타데이터를 제공한다
@Service
public class GatewayTopologyService {
    // 서비스별 디스크립터 정적 목록을 반환한다
    public List<ServiceDescriptor> descriptors() {
        return List.of(
                new ServiceDescriptor("gateway-service", ServicePort.GATEWAY, List.of()),
                new ServiceDescriptor("user-service", ServicePort.USER, List.of(
                        "users",
                        "app_sessions",
                        "billing_products",
                        "payment_orders",
                        "user_reset_passes",
                        "wallet_reset_histories"
                )),
                new ServiceDescriptor("trading-service", ServicePort.TRADING, List.of(
                        "user_wallets",
                        "user_positions",
                        "orders",
                        "order_fills",
                        "ranking_seasons",
                        "season_rankings",
                        "user_equity_snapshots",
                        "market_execution_checkpoints",
                        "trading_outbox_events",
                        "trading_reset_operations"
                )),
                new ServiceDescriptor("market-service", ServicePort.MARKET, List.of(
                        "candle_snapshots",
                        "candle_window_availability",
                        "candle_page_metadata",
                        "market_icons"
                )),
                new ServiceDescriptor("ingestor-service", ServicePort.INGESTOR, List.of()),
                new ServiceDescriptor("community-service", ServicePort.COMMUNITY, List.of(
                        "posts",
                        "post_likes",
                        "comments",
                        "comment_likes",
                        "reports"
                ))
        );
    }
}
