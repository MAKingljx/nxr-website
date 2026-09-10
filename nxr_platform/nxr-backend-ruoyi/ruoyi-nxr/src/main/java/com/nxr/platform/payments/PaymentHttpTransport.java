package com.nxr.platform.payments;

import com.nxr.platform.payments.PaymentModels.HttpRequestData;
import com.nxr.platform.payments.PaymentModels.HttpResponseData;
import java.io.IOException;

interface PaymentHttpTransport {
    HttpResponseData send(HttpRequestData request) throws IOException, InterruptedException;
}
