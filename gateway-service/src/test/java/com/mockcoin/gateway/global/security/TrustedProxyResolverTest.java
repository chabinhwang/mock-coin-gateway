package com.mockcoin.gateway.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TrustedProxyResolverTest {

    @Test
    void ignoresSpoofableLeftmostEntryAddedBeforeTrustedProxyAppendsClientAddress() {
        assertThat(TrustedProxyResolver.resolveClientIp(
                "10.0.0.10",
                "203.0.113.5, 198.51.100.5",
                List.of("10.0.0.0/8")
        )).isEqualTo("198.51.100.5");
    }

    @Test
    void walksRightToLeftAcrossEveryConfiguredTrustedProxy() {
        assertThat(TrustedProxyResolver.resolveClientIp(
                "10.0.0.10",
                "203.0.113.5, 198.51.100.5",
                List.of("10.0.0.0/8", "198.51.100.0/24")
        )).isEqualTo("203.0.113.5");
    }

    @Test
    void ignoresForwardedChainWhenDirectPeerIsUntrusted() {
        assertThat(TrustedProxyResolver.resolveClientIp(
                "192.0.2.10",
                "203.0.113.5, 198.51.100.5",
                List.of("10.0.0.0/8")
        )).isEqualTo("192.0.2.10");
    }

    @Test
    void canonicalizesEquivalentIpv6ClientAddressesToOneRateLimitKey() {
        String compressed = TrustedProxyResolver.resolveClientIp(
                "10.0.0.10",
                "2001:db8::1",
                List.of("10.0.0.0/8")
        );
        String expanded = TrustedProxyResolver.resolveClientIp(
                "10.0.0.10",
                "2001:0db8:0000:0000:0000:0000:0000:0001",
                List.of("10.0.0.0/8")
        );

        assertThat(compressed).isEqualTo(expanded);
    }

    @Test
    void rejectsOutOfRangeTrustedProxyPrefix() {
        assertThat(TrustedProxyResolver.isTrustedProxy("203.0.113.5", List.of("0.0.0.0/-1")))
                .isFalse();
    }
}
