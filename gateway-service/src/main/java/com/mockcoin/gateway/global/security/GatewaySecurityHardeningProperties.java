package com.mockcoin.gateway.global.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

// rate limit, CORS, SSL, 신뢰 프록시 CIDR 등 gateway 보안 설정
@ConfigurationProperties(prefix = "mockcoin.security")
public class GatewaySecurityHardeningProperties {
    private boolean rateLimitEnabled = true;
    private int ipRequestsPerMinute = 100;
    private int authenticatedRequestsPerMinute = 300;
    private int orderRequestsPerMinute = 30;
    private int authRequestsPerWindow = 10;
    private int authWindowSeconds = 60;
    private int marketReadRequestsPerMinute = 240;
    private int marketCandleRequestsPerMinute = 120;
    private int accountReadRequestsPerMinute = 180;
    private int profileWriteRequestsPerMinute = 10;
    private int nicknameRequestsPerMinute = 10;
    private int billingWriteRequestsPerMinute = 10;
    private int iapRequestsPerMinute = 5;
    private int resetRequestsPerMinute = 3;
    private int adminAuthRequestsPerWindow = 10;
    private int adminAuthWindowSeconds = 60;
    private int adminReadRequestsPerMinute = 120;
    private int adminWriteRequestsPerMinute = 30;
    private int adminSensitiveRequestsPerMinute = 5;
    private List<String> corsAllowedOrigins = List.of(
            "https://*.apps.tossmini.com",
            "https://*.private-apps.tossmini.com"
    );
    private boolean requireSsl = false;
    private List<String> trustedProxyCidrs = List.of();

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    public int getIpRequestsPerMinute() {
        return ipRequestsPerMinute;
    }

    public void setIpRequestsPerMinute(int ipRequestsPerMinute) {
        this.ipRequestsPerMinute = ipRequestsPerMinute;
    }

    public int getAuthenticatedRequestsPerMinute() {
        return authenticatedRequestsPerMinute;
    }

    public void setAuthenticatedRequestsPerMinute(int authenticatedRequestsPerMinute) {
        this.authenticatedRequestsPerMinute = authenticatedRequestsPerMinute;
    }

    public int getOrderRequestsPerMinute() {
        return orderRequestsPerMinute;
    }

    public void setOrderRequestsPerMinute(int orderRequestsPerMinute) {
        this.orderRequestsPerMinute = orderRequestsPerMinute;
    }

    public int getAuthRequestsPerWindow() {
        return authRequestsPerWindow;
    }

    public void setAuthRequestsPerWindow(int authRequestsPerWindow) {
        this.authRequestsPerWindow = authRequestsPerWindow;
    }

    public int getAuthWindowSeconds() {
        return authWindowSeconds;
    }

    public void setAuthWindowSeconds(int authWindowSeconds) {
        this.authWindowSeconds = authWindowSeconds;
    }

    public int getMarketReadRequestsPerMinute() {
        return marketReadRequestsPerMinute;
    }

    public void setMarketReadRequestsPerMinute(int marketReadRequestsPerMinute) {
        this.marketReadRequestsPerMinute = marketReadRequestsPerMinute;
    }

    public int getMarketCandleRequestsPerMinute() {
        return marketCandleRequestsPerMinute;
    }

    public void setMarketCandleRequestsPerMinute(int marketCandleRequestsPerMinute) {
        this.marketCandleRequestsPerMinute = marketCandleRequestsPerMinute;
    }

    public int getAccountReadRequestsPerMinute() {
        return accountReadRequestsPerMinute;
    }

    public void setAccountReadRequestsPerMinute(int accountReadRequestsPerMinute) {
        this.accountReadRequestsPerMinute = accountReadRequestsPerMinute;
    }

    public int getProfileWriteRequestsPerMinute() {
        return profileWriteRequestsPerMinute;
    }

    public void setProfileWriteRequestsPerMinute(int profileWriteRequestsPerMinute) {
        this.profileWriteRequestsPerMinute = profileWriteRequestsPerMinute;
    }

    public int getNicknameRequestsPerMinute() {
        return nicknameRequestsPerMinute;
    }

    public void setNicknameRequestsPerMinute(int nicknameRequestsPerMinute) {
        this.nicknameRequestsPerMinute = nicknameRequestsPerMinute;
    }

    public int getBillingWriteRequestsPerMinute() {
        return billingWriteRequestsPerMinute;
    }

    public void setBillingWriteRequestsPerMinute(int billingWriteRequestsPerMinute) {
        this.billingWriteRequestsPerMinute = billingWriteRequestsPerMinute;
    }

    public int getIapRequestsPerMinute() {
        return iapRequestsPerMinute;
    }

    public void setIapRequestsPerMinute(int iapRequestsPerMinute) {
        this.iapRequestsPerMinute = iapRequestsPerMinute;
    }

    public int getResetRequestsPerMinute() {
        return resetRequestsPerMinute;
    }

    public void setResetRequestsPerMinute(int resetRequestsPerMinute) {
        this.resetRequestsPerMinute = resetRequestsPerMinute;
    }

    public int getAdminAuthRequestsPerWindow() {
        return adminAuthRequestsPerWindow;
    }

    public void setAdminAuthRequestsPerWindow(int adminAuthRequestsPerWindow) {
        this.adminAuthRequestsPerWindow = adminAuthRequestsPerWindow;
    }

    public int getAdminAuthWindowSeconds() {
        return adminAuthWindowSeconds;
    }

    public void setAdminAuthWindowSeconds(int adminAuthWindowSeconds) {
        this.adminAuthWindowSeconds = adminAuthWindowSeconds;
    }

    public int getAdminReadRequestsPerMinute() {
        return adminReadRequestsPerMinute;
    }

    public void setAdminReadRequestsPerMinute(int adminReadRequestsPerMinute) {
        this.adminReadRequestsPerMinute = adminReadRequestsPerMinute;
    }

    public int getAdminWriteRequestsPerMinute() {
        return adminWriteRequestsPerMinute;
    }

    public void setAdminWriteRequestsPerMinute(int adminWriteRequestsPerMinute) {
        this.adminWriteRequestsPerMinute = adminWriteRequestsPerMinute;
    }

    public int getAdminSensitiveRequestsPerMinute() {
        return adminSensitiveRequestsPerMinute;
    }

    public void setAdminSensitiveRequestsPerMinute(int adminSensitiveRequestsPerMinute) {
        this.adminSensitiveRequestsPerMinute = adminSensitiveRequestsPerMinute;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
    }

    public boolean isRequireSsl() {
        return requireSsl;
    }

    public void setRequireSsl(boolean requireSsl) {
        this.requireSsl = requireSsl;
    }

    public List<String> getTrustedProxyCidrs() {
        return trustedProxyCidrs;
    }

    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
    }
}
