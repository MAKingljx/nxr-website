package com.nxr.platform.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.nxr.platform.payments.PaymentModels.ConfigurationUpdate;
import com.ruoyi.common.annotation.Log;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentControllerSecurityTest {

    @Test
    void adminConfigurationRequiresFinancePermissionAndDisablesPayloadAudit() throws Exception {
        Method update = AdminPaymentSettingsController.class.getDeclaredMethod("update", String.class, ConfigurationUpdate.class);

        PreAuthorize permission = update.getAnnotation(PreAuthorize.class);
        Log audit = update.getAnnotation(Log.class);

        assertThat(permission.value()).isEqualTo("@ss.hasPermi('nxr:payment:config')");
        assertThat(audit).isNotNull();
        assertThat(audit.isSaveRequestData()).isFalse();
        assertThat(audit.isSaveResponseData()).isFalse();
    }

    @Test
    void alipayParserRejectsDuplicateSignedFields() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PaymentWebhookController.parseForm(
            "trade_no=one&trade_no=two", "application/x-www-form-urlencoded"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void multiConstructorPaymentBeansDeclareTheProductionConstructor() {
        assertThat(autowiredConstructor(AlipayPaymentAdapter.class).getParameterTypes())
            .containsExactly(PaymentHttpTransport.class, com.fasterxml.jackson.databind.ObjectMapper.class);
        assertThat(autowiredConstructor(WechatNativePaymentAdapter.class).getParameterTypes())
            .containsExactly(PaymentHttpTransport.class, com.fasterxml.jackson.databind.ObjectMapper.class);
        assertThat(autowiredConstructor(PaymentCryptoService.class).getParameterCount()).isZero();
    }

    private static java.lang.reflect.Constructor<?> autowiredConstructor(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredConstructors())
            .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
            .reduce((first, second) -> {
                throw new AssertionError("Multiple @Autowired constructors on " + type.getName());
            })
            .orElseThrow(() -> new AssertionError("Missing @Autowired constructor on " + type.getName()));
    }
}
