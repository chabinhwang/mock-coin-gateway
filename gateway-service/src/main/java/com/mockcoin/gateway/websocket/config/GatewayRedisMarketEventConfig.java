package com.mockcoin.gateway.websocket.config;

import com.mockcoin.gateway.websocket.service.GatewayRedisMarketEventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

// Redis market 이벤트 Pub/Sub listener 컨테이너 설정(다중 gateway 인스턴스 fanout용)
@Configuration
@ConditionalOnProperty(prefix = "mockcoin.websocket.redis-market-events", name = "enabled", havingValue = "true")
public class GatewayRedisMarketEventConfig {
    // md:broadcast:* 패턴을 구독하는 Redis listener 컨테이너를 생성한다
    @Bean
    RedisMessageListenerContainer redisMarketEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            GatewayRedisMarketEventListener listener,
            GatewayRedisMarketEventProperties properties
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new PatternTopic(properties.channelPrefix() + "*"));
        return container;
    }
}
