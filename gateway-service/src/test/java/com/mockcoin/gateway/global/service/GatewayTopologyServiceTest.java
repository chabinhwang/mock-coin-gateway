package com.mockcoin.gateway.global.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mockcoin.shared.service.ServiceDescriptor;
import com.mockcoin.shared.service.ServicePort;
import org.junit.jupiter.api.Test;

class GatewayTopologyServiceTest {

    private final GatewayTopologyService topologyService = new GatewayTopologyService();

    @Test
    void descriptorsMatchTheStableBaselineTableSubset() {
        assertThat(topologyService.descriptors()).containsExactly(
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
