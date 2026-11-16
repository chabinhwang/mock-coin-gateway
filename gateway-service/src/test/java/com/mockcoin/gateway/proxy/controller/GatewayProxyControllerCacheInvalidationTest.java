package com.mockcoin.gateway.proxy.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mockcoin.gateway.global.security.GatewayTokenAuthenticator;
import com.mockcoin.gateway.proxy.service.GatewayProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class GatewayProxyControllerCacheInvalidationTest {

    @Test
    void successfulLogoutInvalidatesPresentedAccessToken() {
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer access-token");
        when(proxy.proxyToUser(
                eq(HttpMethod.POST),
                eq("/api/v1/auth/logout"),
                eq(request),
                eq(response),
                eq(true)
        )).thenReturn(HttpStatus.OK);

        new GatewayProxyController(proxy, authenticator).postAuth("/logout", request, response);

        verify(authenticator).invalidate("Bearer access-token");
    }

    @Test
    void failedLogoutDoesNotInvalidateAffirmativeCache() {
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(proxy.proxyToUser(any(), any(), any(), any(), eq(true))).thenReturn(HttpStatus.UNAUTHORIZED);

        new GatewayProxyController(proxy, authenticator).postAuth("logout", request, response);

        verify(authenticator, never()).invalidate(any());
    }

    @Test
    void refreshDoesNotPretendToRetirePresentedAccessToken() {
        GatewayProxyService proxy = mock(GatewayProxyService.class);
        GatewayTokenAuthenticator authenticator = mock(GatewayTokenAuthenticator.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(proxy.proxyToUser(any(), any(), any(), any(), eq(true))).thenReturn(HttpStatus.OK);

        new GatewayProxyController(proxy, authenticator).postAuth("refresh", request, response);

        verify(authenticator, never()).invalidate(any());
    }
}
