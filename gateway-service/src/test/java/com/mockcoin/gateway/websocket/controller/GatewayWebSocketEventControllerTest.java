package com.mockcoin.gateway.websocket.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import com.mockcoin.gateway.websocket.service.GatewayRedisPrivateEventPublisher;
import com.mockcoin.gateway.websocket.service.GatewayRedisPublicEventPublisher;
import com.mockcoin.gateway.websocket.service.GatewayWebSocketBroadcaster;
import com.mockcoin.gateway.websocket.domain.PrivateGatewayEvent;
import com.mockcoin.gateway.websocket.domain.PublicGatewayEvent;
import com.mockcoin.shared.api.ApiException;
import org.junit.jupiter.api.Test;

class GatewayWebSocketEventControllerTest {
    @Test
    void publicEventPublishesToRedisInsteadOfLocalBroadcastWhenRedisMarketFanoutSucceeds() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publicPublisher = mock(GatewayRedisPublicEventPublisher.class);
        GatewayRedisPrivateEventPublisher privatePublisher = mock(GatewayRedisPrivateEventPublisher.class);
        GatewayWebSocketEventController controller = new GatewayWebSocketEventController(broadcaster, publicPublisher, privatePublisher);
        PublicGatewayEvent event = new PublicGatewayEvent("ticker.update", "KRW-BTC", Instant.parse("2026-04-25T00:00:00Z"), Map.of("tradePrice", 100));
        when(publicPublisher.publish(event)).thenReturn(new GatewayRedisPublicEventPublisher.PublishResult(true, "published_to_gateway_market_redis"));

        var response = controller.publicEvent(event);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success().delivered()).isZero();
        verify(publicPublisher).publish(event);
        verify(broadcaster, never()).broadcastPublic(event);
    }

    @Test
    void marketListRedisFanoutReportsAsynchronousDeliveryInsteadOfNoSubscribers() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publicPublisher = mock(GatewayRedisPublicEventPublisher.class);
        GatewayRedisPrivateEventPublisher privatePublisher = mock(GatewayRedisPrivateEventPublisher.class);
        GatewayWebSocketEventController controller = new GatewayWebSocketEventController(broadcaster, publicPublisher, privatePublisher);
        PublicGatewayEvent event = new PublicGatewayEvent(
                "marketList.update",
                null,
                Instant.parse("2026-04-25T00:00:00Z"),
                Map.of("markets", java.util.List.of(Map.of("market", "KRW-BTC", "currentPrice", 100)))
        );
        when(publicPublisher.publish(event)).thenReturn(
                new GatewayRedisPublicEventPublisher.PublishResult(true, "published_to_gateway_market_redis"));

        var response = controller.publicEvent(event);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success().delivered()).isEqualTo(-1);
        verify(broadcaster, never()).broadcastPublic(event);
    }

    @Test
    void publicEventFallsBackToLocalBroadcastWhenRedisMarketFanoutSkipped() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publicPublisher = mock(GatewayRedisPublicEventPublisher.class);
        GatewayRedisPrivateEventPublisher privatePublisher = mock(GatewayRedisPrivateEventPublisher.class);
        GatewayWebSocketEventController controller = new GatewayWebSocketEventController(broadcaster, publicPublisher, privatePublisher);
        PublicGatewayEvent event = new PublicGatewayEvent("candle.reconcile", "KRW-BTC", Instant.parse("2026-04-25T00:00:00Z"), Map.of("interval", "1m", "close", 100));
        when(publicPublisher.publish(event)).thenReturn(new GatewayRedisPublicEventPublisher.PublishResult(false, "redis_market_events_disabled"));
        when(broadcaster.broadcastPublic(event)).thenReturn(2);

        var response = controller.publicEvent(event);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success().delivered()).isEqualTo(2);
        verify(publicPublisher).publish(event);
        verify(broadcaster).broadcastPublic(event);
    }

    @Test
    void publicEventRejectsInvalidTypeBeforeRedisPublish() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publicPublisher = mock(GatewayRedisPublicEventPublisher.class);
        GatewayRedisPrivateEventPublisher privatePublisher = mock(GatewayRedisPrivateEventPublisher.class);
        GatewayWebSocketEventController controller = new GatewayWebSocketEventController(broadcaster, publicPublisher, privatePublisher);
        PublicGatewayEvent event = new PublicGatewayEvent("trade.update", "KRW-BTC", Instant.parse("2026-04-25T00:00:00Z"), Map.of("price", 100));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.publicEvent(event))
                .isInstanceOf(ApiException.class);
        verify(publicPublisher, never()).publish(event);
        verify(broadcaster, never()).broadcastPublic(event);
    }

    @Test
    void privateEventPublishesToRedisInsteadOfLocalBroadcastWhenRedisPrivateFanoutSucceeds() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publicPublisher = mock(GatewayRedisPublicEventPublisher.class);
        GatewayRedisPrivateEventPublisher publisher = mock(GatewayRedisPrivateEventPublisher.class);
        GatewayWebSocketEventController controller = new GatewayWebSocketEventController(broadcaster, publicPublisher, publisher);
        PrivateGatewayEvent event = new PrivateGatewayEvent("order.update", Instant.parse("2026-04-25T00:00:00Z"), Map.of("orderId", 123));
        when(publisher.publish(777L, event)).thenReturn(new GatewayRedisPrivateEventPublisher.PublishResult(true, "published_to_gateway_private_redis"));

        var response = controller.privateEvent(777L, event);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success().delivered()).isZero();
        verify(publisher).publish(777L, event);
        verify(broadcaster, never()).broadcastPrivate(777L, event);
    }

    @Test
    void privateEventFallsBackToLocalBroadcastWhenRedisPrivateFanoutSkipped() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publicPublisher = mock(GatewayRedisPublicEventPublisher.class);
        GatewayRedisPrivateEventPublisher publisher = mock(GatewayRedisPrivateEventPublisher.class);
        GatewayWebSocketEventController controller = new GatewayWebSocketEventController(broadcaster, publicPublisher, publisher);
        PrivateGatewayEvent event = new PrivateGatewayEvent("portfolio.update", Instant.parse("2026-04-25T00:00:00Z"), Map.of("totalAssetValue", 100));
        when(publisher.publish(777L, event)).thenReturn(new GatewayRedisPrivateEventPublisher.PublishResult(false, "redis_private_events_disabled"));
        when(broadcaster.broadcastPrivate(777L, event)).thenReturn(1);

        var response = controller.privateEvent(777L, event);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success().delivered()).isEqualTo(1);
        verify(broadcaster).broadcastPrivate(777L, event);
    }

    @Test
    void privateEventRejectsInvalidTypeBeforeRedisPublish() {
        GatewayWebSocketBroadcaster broadcaster = mock(GatewayWebSocketBroadcaster.class);
        GatewayRedisPublicEventPublisher publicPublisher = mock(GatewayRedisPublicEventPublisher.class);
        GatewayRedisPrivateEventPublisher publisher = mock(GatewayRedisPrivateEventPublisher.class);
        GatewayWebSocketEventController controller = new GatewayWebSocketEventController(broadcaster, publicPublisher, publisher);
        PrivateGatewayEvent event = new PrivateGatewayEvent("ticker.update", Instant.parse("2026-04-25T00:00:00Z"), Map.of("tradePrice", 100));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.privateEvent(777L, event))
                .isInstanceOf(ApiException.class);
        verify(publisher, never()).publish(777L, event);
        verify(broadcaster, never()).broadcastPrivate(777L, event);
    }
}
