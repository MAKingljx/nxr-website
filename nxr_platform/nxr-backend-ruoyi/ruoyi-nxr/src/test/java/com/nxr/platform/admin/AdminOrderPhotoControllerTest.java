package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.customer.CustomerOrderPhotoService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AdminOrderPhotoControllerTest {
    @Test
    void currentOrderIsAuthorizedAndMustActuallyContainThePhoto() {
        var photos = mock(CustomerOrderPhotoService.class);
        var scope = mock(OrderAccessScopeService.class);
        var image = new ByteArrayResource(new byte[]{1,2,3});
        when(photos.readAttached(22, 7)).thenReturn(image);
        var controller = new AdminOrderPhotoController(photos, scope);
        assertThat(controller.preview(7, 22L).getBody()).isSameAs(image);
        verify(scope).requireAccessibleOrder(22L);
        verify(photos, never()).attachedOrderId(anyLong());
        when(photos.readAttached(23, 7)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.preview(7, 23L)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void legacyPreviewKeepsOriginalOrderScopeAndDeniesBeforeReadingImage() {
        var photos = mock(CustomerOrderPhotoService.class);
        var scope = mock(OrderAccessScopeService.class);
        when(photos.attachedOrderId(7)).thenReturn(11L);
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(scope).requireAccessibleOrder(11L);
        var controller = new AdminOrderPhotoController(photos, scope);
        assertThatThrownBy(() -> controller.preview(7, null)).isInstanceOf(ResponseStatusException.class);
        verify(photos, never()).readAttached(anyLong(), anyLong());
    }
}
