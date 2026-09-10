package com.nxr.platform.payments;

import com.nxr.platform.payments.PaymentModels.HttpRequestData;
import com.nxr.platform.payments.PaymentModels.HttpResponseData;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
final class JdkPaymentHttpTransport implements PaymentHttpTransport {

    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @Override
    public HttpResponseData send(HttpRequestData request) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(Duration.ofSeconds(20));
        request.headers().forEach(builder::header);
        if (request.body() == null || request.body().isEmpty()) {
            builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(request.method(), HttpRequest.BodyPublishers.ofString(request.body()));
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new HttpResponseData(response.statusCode(), response.headers().map(), response.body());
    }
}
