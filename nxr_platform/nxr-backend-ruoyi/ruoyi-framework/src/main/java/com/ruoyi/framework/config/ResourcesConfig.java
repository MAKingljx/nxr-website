package com.ruoyi.framework.config;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.framework.interceptor.RepeatSubmitInterceptor;

/**
 * 通用配置
 * 
 * @author ruoyi
 */
@Configuration
public class ResourcesConfig implements WebMvcConfigurer
{
    @Value("${nxr.public-site.base-url:http://127.0.0.1:3000}")
    private String trackingCustomerBase = "http://127.0.0.1:3000";

    @Value("${nxr.tracking.allowed-origins:}")
    private String trackingAllowedOrigins = "";
    @Autowired
    private RepeatSubmitInterceptor repeatSubmitInterceptor;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry)
    {
        /** 本地文件上传路径 */
        registry.addResourceHandler(Constants.RESOURCE_PREFIX + "/**")
                .addResourceLocations("file:" + RuoYiConfig.getProfile() + "/");

        /** swagger配置 */
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/springfox-swagger-ui/")
                .setCacheControl(CacheControl.maxAge(5, TimeUnit.HOURS).cachePublic());
    }

    /**
     * 自定义拦截规则
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(repeatSubmitInterceptor).addPathPatterns("/**");
    }

    /**
     * 跨域配置
     */
    @Bean
    public CorsFilter corsFilter()
    {
        CorsConfiguration config = new CorsConfiguration();
        // 设置访问源地址
        config.addAllowedOriginPattern("*");
        // 设置访问源请求头
        config.addAllowedHeader("*");
        // 设置访问源请求方法
        config.addAllowedMethod("*");
        // 有效期 1800秒
        config.setMaxAge(1800L);
        // 添加映射路径，拦截一切请求
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration privateTracking = new CorsConfiguration();
        String customerOrigin = trackingOrigin(trackingCustomerBase, true);
        if (customerOrigin != null) privateTracking.addAllowedOrigin(customerOrigin);
        for (String value : trackingAllowedOrigins.split(",")) {
            String origin = trackingOrigin(value, false);
            if (origin != null) privateTracking.addAllowedOrigin(origin);
        }
        privateTracking.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
        privateTracking.setAllowedHeaders(java.util.List.of("Content-Type", "X-NXR-Tracking-Token", "X-NXR-Tracking-Access"));
        privateTracking.setMaxAge(1800L);
        // The specific route must precede the legacy catch-all in this source's lookup order.
        source.registerCorsConfiguration("/api/public/merchant-order-tracking/**", privateTracking);
        source.registerCorsConfiguration("/**", config);
        // 返回新的CorsFilter
        return new CorsFilter(source);
    }

    private static String trackingOrigin(String value, boolean allowPath)
    {
        try {
            java.net.URI uri = java.net.URI.create(value == null ? "" : value.trim());
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!allowPath && uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) return null;
            return uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException ignored) { return null; }
    }
}
