package com.mockcoin.gateway.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// admin 콘솔 정적 리소스를 classpath:/admin-ui/에서 서빙한다
@Configuration
@ConditionalOnProperty(prefix = "mockcoin.admin", name = "enabled", havingValue = "true")
public class GatewayAdminWebMvcConfig implements WebMvcConfigurer {
    private final GatewayAdminProperties properties;

    // 관리자 설정을 주입받는다.
    public GatewayAdminWebMvcConfig(GatewayAdminProperties properties) {
        this.properties = properties;
    }

    // HTML/JS/CSS/아이콘만 정적 서빙한다. /admin/api/** 는 facade 컨트롤러가 담당한다
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String basePath = properties.basePath();
        registry.addResourceHandler(
                        basePath + "/*.html",
                        basePath + "/*.js",
                        basePath + "/*.css",
                        basePath + "/*.png",
                        basePath + "/*.ico",
                        basePath + "/*.svg",
                        basePath + "/*.webmanifest"
                )
                .addResourceLocations("classpath:/admin-ui/");
    }
}
