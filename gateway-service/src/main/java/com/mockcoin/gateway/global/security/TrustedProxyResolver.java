package com.mockcoin.gateway.global.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * X-Forwarded-For / trusted-proxy CIDR 기반으로 클라이언트 IP를 결정하는 공용 유틸.
 * REST 필터와 WebSocket 세션 등록 모두에서 같은 정책을 사용한다.
 */
// X-Forwarded-For 및 신뢰 프록시 CIDR을 기반으로 실제 클라이언트 IP를 해석하는 유틸
public final class TrustedProxyResolver {
    private TrustedProxyResolver() {
    }

    // 신뢰 프록시 체인을 우측부터 제거해 첫 비신뢰 hop을 실제 클라이언트 IP로 사용한다
    // 직접 연결한 peer가 신뢰 프록시일 때만 헤더를 보며, 건너뛸 프록시는 모두 CIDR에 넣어야 클라이언트가 위조한 왼쪽 값을 막는다.
    public static String resolveClientIp(String remoteAddr, String forwardedForHeader, List<String> trustedProxyCidrs) {
        String rawFallback = remoteAddr == null || remoteAddr.isBlank() ? "unknown" : remoteAddr.trim();
        String normalizedFallback = normalizeIpLiteral(rawFallback);
        String fallback = normalizedFallback == null ? rawFallback : normalizedFallback;
        if (forwardedForHeader == null
                || forwardedForHeader.isBlank()
                || !isTrustedProxy(fallback, trustedProxyCidrs)) {
            return fallback;
        }
        String[] parts = forwardedForHeader.split(",");
        String furthestTrustedHop = null;
        for (int index = parts.length - 1; index >= 0; index--) {
            String rawCandidate = parts[index].trim();
            if (rawCandidate.isEmpty()) {
                continue;
            }
            String candidate = normalizeIpLiteral(rawCandidate);
            if (candidate == null) {
                return fallback;
            }
            furthestTrustedHop = candidate;
            if (!isTrustedProxy(candidate, trustedProxyCidrs)) {
                return candidate;
            }
        }
        return furthestTrustedHop == null ? fallback : furthestTrustedHop;
    }

    // remoteAddr이 설정된 신뢰 프록시 CIDR 중 하나에 속하는지 확인한다
    public static boolean isTrustedProxy(String remoteAddr, List<String> trustedProxyCidrs) {
        if (remoteAddr == null || trustedProxyCidrs == null || trustedProxyCidrs.isEmpty()) {
            return false;
        }
        byte[] addrBytes = parseIpLiteral(remoteAddr);
        if (addrBytes == null) {
            return false;
        }
        for (String cidr : trustedProxyCidrs) {
            if (cidr != null && !cidr.isBlank() && matchesCidr(addrBytes, cidr.trim())) {
                return true;
            }
        }
        return false;
    }

    // 주소 바이트가 CIDR(접두 길이가 없으면 단일 주소) 범위에 속하는지 확인한다(형식 오류 시 false).
    private static boolean matchesCidr(byte[] addrBytes, String cidr) {
        try {
            String networkPart;
            int prefixLength;
            if (cidr.contains("/")) {
                String[] parts = cidr.split("/", 2);
                networkPart = parts[0];
                prefixLength = Integer.parseInt(parts[1]);
            } else {
                networkPart = cidr;
                prefixLength = addrBytes.length * 8;
            }

            byte[] networkBytes = parseIpLiteral(networkPart);
            if (networkBytes == null || addrBytes.length != networkBytes.length) {
                return false;
            }
            if (prefixLength < 0 || prefixLength > addrBytes.length * 8) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            int remaining = prefixLength % 8;
            if (remaining > 0) {
                int mask = (0xFF << (8 - remaining)) & 0xFF;
                return (addrBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    // 대괄호를 벗긴 IP 리터럴을 표준 표기로 바꾸고, IP가 아니면 null을 반환한다.
    private static String normalizeIpLiteral(String value) {
        String candidate = value;
        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        byte[] address = parseIpLiteral(candidate);
        if (address == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    // IPv4/IPv6 리터럴을 바이트 배열로 파싱하고, IP 형식이 아니면 호스트 이름 조회 없이 null을 반환한다.
    private static byte[] parseIpLiteral(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (!looksLikeIpv4(candidate) && !looksLikeIpv6(candidate)) {
            return null;
        }
        if (looksLikeIpv4(candidate)) {
            String[] octets = candidate.split("\\.");
            byte[] address = new byte[octets.length];
            for (int index = 0; index < octets.length; index++) {
                address[index] = (byte) Integer.parseInt(octets[index]);
            }
            return address;
        }
        try {
            return InetAddress.getByName(candidate).getAddress();
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    // 점으로 나뉜 0~255 숫자 4개로 된 IPv4 형식인지 확인한다.
    private static boolean looksLikeIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return false;
            }
            try {
                if (Integer.parseInt(octet) > 255) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    // ':'를 포함하고 16진수·':'·'.'로만 이뤄진 IPv6 형식인지 확인한다(zone ID '%'는 거부).
    private static boolean looksLikeIpv6(String value) {
        if (!value.contains(":") || value.contains("%")) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != ':'
                    && character != '.'
                    && Character.digit(character, 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
