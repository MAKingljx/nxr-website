package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

class TrackingClientAddressResolverTest {
    private MockHttpServletRequest request(String peer,String forwarded){
        var request=new MockHttpServletRequest();request.setRemoteAddr(peer);if(forwarded!=null)request.addHeader("X-Forwarded-For",forwarded);return request;
    }
    @Test void ignoresForwardedHeadersUnlessTheDirectPeerIsExplicitlyTrusted(){
        var resolver=new TrackingClientAddressResolver();
        assertThat(resolver.source(request("203.0.113.20","198.51.100.7"))).isEqualTo("203.0.113.20");
        ReflectionTestUtils.setField(resolver,"trustedProxies","127.0.0.1,10.0.0.3");
        assertThat(resolver.source(request("203.0.113.20","198.51.100.7"))).isEqualTo("203.0.113.20");
        assertThat(resolver.source(request("127.0.0.1","1.1.1.1, 198.51.100.7, 10.0.0.3"))).isEqualTo("198.51.100.7");
        assertThat(resolver.source(request("127.0.0.1","198.51.100.7, forged"))).isEqualTo("127.0.0.1");
        assertThat(resolver.source(request("127.0.0.1",null))).isEqualTo("127.0.0.1");
    }
}
