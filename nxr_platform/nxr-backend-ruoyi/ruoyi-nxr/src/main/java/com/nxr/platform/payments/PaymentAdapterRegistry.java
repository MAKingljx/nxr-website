package com.nxr.platform.payments;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
final class PaymentAdapterRegistry {

    private final Map<String, PaymentAdapter> adapters;

    PaymentAdapterRegistry(List<PaymentAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(PaymentAdapter::provider, Function.identity()));
    }

    PaymentAdapter require(String provider) {
        PaymentProviderSpec spec = PaymentProviderSpec.require(provider);
        PaymentAdapter adapter = adapters.get(spec.code());
        if (adapter == null) {
            throw PaymentValidationException.unavailable("Payment provider adapter is unavailable");
        }
        return adapter;
    }
}
