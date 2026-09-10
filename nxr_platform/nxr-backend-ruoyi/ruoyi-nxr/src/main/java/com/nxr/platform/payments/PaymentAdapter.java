package com.nxr.platform.payments;

import com.nxr.platform.payments.PaymentModels.CaptureResult;
import com.nxr.platform.payments.PaymentModels.CheckoutContext;
import com.nxr.platform.payments.PaymentModels.ProviderCheckout;
import com.nxr.platform.payments.PaymentModels.ProviderContext;
import com.nxr.platform.payments.PaymentModels.VerifiedPayment;
import com.nxr.platform.payments.PaymentModels.WebhookRequest;

interface PaymentAdapter {
    String provider();

    ProviderCheckout createCheckout(CheckoutContext context);

    default CaptureResult capture(CheckoutContext context, String providerOrderId) {
        throw PaymentValidationException.badRequest("This payment provider does not use server capture");
    }

    VerifiedPayment verifyWebhook(ProviderContext context, WebhookRequest request);
}
