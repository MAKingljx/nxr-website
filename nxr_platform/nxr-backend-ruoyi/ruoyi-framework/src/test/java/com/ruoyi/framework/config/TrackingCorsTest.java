package com.ruoyi.framework.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.filter.CorsFilter;

class TrackingCorsTest {
    private MockHttpServletResponse request(CorsFilter filter,String path,String method,String origin,boolean preflight) throws Exception {
        var request=new MockHttpServletRequest(method,path);request.setServletPath(path);request.setScheme("http");request.setServerName("localhost");request.setServerPort(8088);
        if(origin!=null)request.addHeader("Origin",origin);
        if(preflight){request.addHeader("Access-Control-Request-Method","GET");request.addHeader("Access-Control-Request-Headers","X-NXR-Tracking-Token,X-NXR-Tracking-Access");}
        var response=new MockHttpServletResponse();filter.doFilter(request,response,new MockFilterChain());return response;
    }
    @Test void trackingUsesSpecificAllowedOriginsAndOtherRoutesKeepTheirExistingCors() throws Exception {
        var config=new ResourcesConfig();
        ReflectionTestUtils.setField(config,"trackingCustomerBase","https://customer.example/portal");
        ReflectionTestUtils.setField(config,"trackingAllowedOrigins","http://localhost:3002,https://*.invalid,https://bad.example/path");
        CorsFilter filter=config.corsFilter();
        for(String path:new String[]{"/api/public/merchant-order-tracking/verify","/api/public/merchant-order-tracking/order","/api/public/merchant-order-tracking/photos/1/front","/api/public/merchant-order-tracking/old-token"}) {
            var allowed=request(filter,path,"OPTIONS","https://customer.example",true);
            assertThat(allowed.getStatus()).isEqualTo(200);assertThat(allowed.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://customer.example");
            assertThat(request(filter,path,"OPTIONS","http://localhost:3002",true).getStatus()).isEqualTo(200);
            assertThat(request(filter,path,"OPTIONS","https://attacker.invalid",true).getStatus()).isEqualTo(403);
            assertThat(request(filter,path,"GET","https://attacker.invalid",false).getStatus()).isEqualTo(403);
            assertThat(request(filter,path,"GET",null,false).getStatus()).isEqualTo(200);
        }
        var unrelated=request(filter,"/api/public/overview","GET","https://attacker.invalid",false);
        assertThat(unrelated.getStatus()).isEqualTo(200);assertThat(unrelated.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://attacker.invalid");
        assertThat(request(filter,"/api/public/merchant-order-tracking/order","GET","https://bad.example",false).getStatus()).isEqualTo(403);
    }
}
