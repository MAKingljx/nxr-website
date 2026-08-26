package com.nxr.platform.customer;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Creates merchant orders independently so one invalid row never rolls back valid rows. */
@Service
public class MerchantBulkOrderService {

    private final CustomerPortalService customerPortalService;
    private final OrderFulfillmentService orderFulfillmentService;

    public MerchantBulkOrderService(
        CustomerPortalService customerPortalService,
        OrderFulfillmentService orderFulfillmentService
    ) {
        this.customerPortalService = customerPortalService;
        this.orderFulfillmentService = orderFulfillmentService;
    }

    public OrderFulfillmentService.MerchantImportResult createOrders(long customerId, BulkOrderRequest request) {
        List<CustomerPortalService.CreateOrderRequest> orders = request == null || request.orders() == null
            ? List.of() : request.orders();
        if (orders.isEmpty() || orders.size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A merchant import must contain between 1 and 200 orders");
        }
        long jobId = orderFulfillmentService.createMerchantImportJob(customerId, request.sourceName(), orders.size());
        int rowNo = 1;
        for (CustomerPortalService.CreateOrderRequest orderRequest : orders) {
            try {
                CustomerPortalService.OrderDetailResponse order = customerPortalService.createOrder(customerId, orderRequest);
                orderFulfillmentService.recordMerchantImportRow(jobId, rowNo, order.id(), null);
            } catch (ResponseStatusException exception) {
                orderFulfillmentService.recordMerchantImportRow(jobId, rowNo, null, exception.getReason());
            } catch (RuntimeException exception) {
                orderFulfillmentService.recordMerchantImportRow(jobId, rowNo, null, "Order row could not be created");
            }
            rowNo += 1;
        }
        return orderFulfillmentService.finishMerchantImportJob(jobId);
    }

    public record BulkOrderRequest(String sourceName, List<CustomerPortalService.CreateOrderRequest> orders) {
    }
}
