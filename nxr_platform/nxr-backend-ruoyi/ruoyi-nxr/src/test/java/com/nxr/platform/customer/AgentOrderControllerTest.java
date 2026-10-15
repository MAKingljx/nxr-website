package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;

class AgentOrderControllerTest {
    @Test void everyEndpointUsesTheLivePartnerScopeAndPrivateResponseHeaders() {
        var scope=mock(AgentOperatorScopeService.class); var service=mock(AgentOrderService.class);
        when(scope.currentMerchant(7L)).thenReturn(41L);
        var controller=new AgentOrderController(scope,service);
        controller.options(7L);controller.list(7L,1,20,"query","ready");controller.detail(7L,8L);controller.create(7L,null);
        verify(scope,times(4)).currentMerchant(7L);
        verify(service).options(41L);verify(service).list(41L,1,20,"query","ready");verify(service).detail(41L,8L);verify(service).create(41L,null);
        var response=new MockHttpServletResponse();controller.privateResponses(response);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders("Vary")).contains("X-NXR-Agent-Id");
        assertThat(AgentOrderController.class.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("@ss.hasAnyPermi('nxr:agent:workbench,nxr:agent:manage')");
    }

    @Test void newOrderRequestCannotAssignTenantOrWorkflowState() {
        var mapper=new ObjectMapper();
        for(String field:java.util.List.of("merchantCustomerId","statusCode","orderNo","clientId")) {
            assertThatThrownBy(()->mapper.readValue("{\"clientName\":\"A\",\""+field+"\":\"forbidden\"}",AgentOrderService.CreateRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        }
    }
}
