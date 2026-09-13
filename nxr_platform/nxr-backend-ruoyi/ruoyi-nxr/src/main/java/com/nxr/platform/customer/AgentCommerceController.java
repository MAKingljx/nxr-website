package com.nxr.platform.customer;

import com.nxr.platform.admission.OrderAdmissionService;
import com.nxr.platform.commerce.CommercePolicyService;
import com.nxr.platform.commerce.CustomerCommercePolicyController;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Enterprise-owned operations use the customer services after resolving a backend operator's company. */
@RestController
@RequestMapping("/api/admin/agent")
@PreAuthorize("@ss.hasAnyPermi('nxr:agent:workbench,nxr:agent:manage')")
public class AgentCommerceController {
    private static final String COMPANY="X-NXR-Agent-Id";
    private final AgentOperatorScopeService scope;
    private final MerchantBatchService batches;
    private final CustomerPortalService portal;
    private final OrderFulfillmentService fulfillment;
    private final OrderAdmissionService admission;
    private final MerchantWalletService wallet;
    private final CustomerOrderPhotoService photos;
    private final CommercePolicyService commerce;
    public AgentCommerceController(AgentOperatorScopeService scope,MerchantBatchService batches,CustomerPortalService portal,
        OrderFulfillmentService fulfillment,OrderAdmissionService admission,MerchantWalletService wallet,
        CustomerOrderPhotoService photos,CommercePolicyService commerce) {
        this.scope=scope;this.batches=batches;this.portal=portal;this.fulfillment=fulfillment;
        this.admission=admission;this.wallet=wallet;this.photos=photos;this.commerce=commerce;
    }
    private long owner(Long company) { return scope.currentMerchant(company); }
    @GetMapping("/merchant/batches")
    public MerchantBatchService.BatchPage batches(@RequestHeader(name=COMPANY,required=false) Long company,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return batches.listMerchantBatches(owner(company),page,pageSize);
    }
    @GetMapping("/merchant/batches/{batchNo}")
    public MerchantBatchService.BatchDetail batch(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String batchNo) {
        return batches.requireMerchantBatch(owner(company),batchNo);
    }
    @PostMapping("/merchant/batches/{batchNo}/inbound-shipment")
    public MerchantBatchService.BatchDetail inbound(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String batchNo,
        @RequestBody MerchantBatchService.BatchShipmentRequest request) { return batches.createInboundShipment(owner(company),batchNo,request); }
    @PostMapping("/merchant/batches/{batchNo}/orders/{orderNo}/tracking-token/rotate")
    public MerchantBatchService.TrackingTokenResponse rotate(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String batchNo,@PathVariable String orderNo) {
        return batches.rotateTrackingToken(owner(company),batchNo,orderNo);
    }
    @DeleteMapping("/merchant/batches/{batchNo}/orders/{orderNo}/tracking-token")
    public Map<String,Boolean> revoke(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String batchNo,@PathVariable String orderNo) {
        batches.revokeTrackingToken(owner(company),batchNo,orderNo);return Map.of("revoked",true);
    }
    @GetMapping("/orders/{orderNo}")
    public CustomerPortalService.OrderDetailResponse order(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String orderNo) {
        return portal.requireCustomerOrder(owner(company),orderNo);
    }
    @GetMapping("/orders/{orderNo}/admission")
    public OrderAdmissionService.AdmissionResponse orderAdmission(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String orderNo) {
        return admission.requireCustomerAdmission(owner(company),orderNo);
    }
    @PostMapping("/orders/{orderNo}/admission/accept-terms")
    public OrderAdmissionService.AdmissionResponse acceptTerms(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String orderNo,
        @RequestBody OrderAdmissionService.AcceptTermsRequest request) { return admission.acceptTerms(owner(company),orderNo,request); }
    @PostMapping("/orders/{orderNo}/admission/resubmit")
    public OrderAdmissionService.AdmissionResponse resubmit(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String orderNo,
        @RequestBody OrderAdmissionService.ResubmitRequest request) { return admission.resubmit(owner(company),orderNo,request); }
    @PostMapping("/orders/{orderNo}/wallet-payment")
    public CustomerPortalService.OrderDetailResponse pay(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String orderNo,
        @RequestBody CustomerPortalService.WalletPaymentRequest request) { return portal.payOrderFromWallet(owner(company),orderNo,request); }
    @GetMapping("/orders/{orderNo}/packing-slip")
    public OrderFulfillmentService.PackingSlip packingSlip(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String orderNo) {
        return fulfillment.requirePackingSlip(owner(company),orderNo);
    }
    @GetMapping("/orders/{orderNo}/operations")
    public OrderFulfillmentService.CustomerOperationsResponse operations(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable String orderNo) {
        return fulfillment.loadCustomerOperations(owner(company),orderNo);
    }
    @GetMapping("/addresses")
    public List<OrderFulfillmentService.CustomerAddress> addresses(@RequestHeader(name=COMPANY,required=false) Long company) { return fulfillment.listAddresses(owner(company)); }
    @PostMapping("/addresses")
    public OrderFulfillmentService.CustomerAddress createAddress(@RequestHeader(name=COMPANY,required=false) Long company,@RequestBody OrderFulfillmentService.AddressRequest request) {
        return fulfillment.saveAddress(owner(company),null,request);
    }
    @PutMapping("/addresses/{addressId}")
    public OrderFulfillmentService.CustomerAddress updateAddress(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable long addressId,@RequestBody OrderFulfillmentService.AddressRequest request) {
        return fulfillment.saveAddress(owner(company),addressId,request);
    }
    @DeleteMapping("/addresses/{addressId}")
    public Map<String,Boolean> deleteAddress(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable long addressId) {
        fulfillment.deleteAddress(owner(company),addressId);return Map.of("success",true);
    }
    @GetMapping("/merchant/profile")
    public MerchantWalletService.MerchantProfile profile(@RequestHeader(name=COMPANY,required=false) Long company) { return wallet.merchantProfile(owner(company)); }
    @PutMapping("/merchant/profile")
    public MerchantWalletService.MerchantProfile profile(@RequestHeader(name=COMPANY,required=false) Long company,@RequestBody MerchantWalletService.MerchantProfileRequest request) {
        return wallet.saveMerchantProfile(owner(company),request);
    }
    @GetMapping("/merchant/wallets")
    public List<MerchantWalletService.WalletBalance> wallets(@RequestHeader(name=COMPANY,required=false) Long company) { return wallet.listWallets(owner(company)); }
    @GetMapping("/merchant/wallet-transactions")
    public MerchantWalletService.WalletTransactionPage transactions(@RequestHeader(name=COMPANY,required=false) Long company,
        @RequestParam(required=false) String currencyCode,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return wallet.listTransactions(owner(company),currencyCode,page,pageSize);
    }
    @GetMapping("/merchant/wallet-recharges")
    public MerchantWalletService.RechargePage recharges(@RequestHeader(name=COMPANY,required=false) Long company,
        @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return wallet.listRecharges(owner(company),status,page,pageSize);
    }
    @PostMapping("/merchant/wallet-recharges")
    public MerchantWalletService.RechargeRecord recharge(@RequestHeader(name=COMPANY,required=false) Long company,@RequestBody MerchantWalletService.RechargeRequest request) {
        return wallet.createRecharge(owner(company),request);
    }
    @GetMapping("/order-admission/config")
    public OrderAdmissionService.PublicConfig config(@RequestHeader(name=COMPANY,required=false) Long company) { owner(company);return admission.publicConfig(); }
    @GetMapping("/shipping-options")
    public List<OrderFulfillmentService.ShippingOption> shippingOptions(@RequestHeader(name=COMPANY,required=false) Long company,
        @RequestParam(required=false) String country,@RequestParam(required=false) String currencyCode) {
        owner(company);return fulfillment.listShippingOptions(country,currencyCode,false);
    }
    @GetMapping("/service-prices")
    public List<OrderFulfillmentService.ServicePrice> servicePrices(@RequestHeader(name=COMPANY,required=false) Long company) { owner(company);return fulfillment.activeServicePrices(); }
    @GetMapping("/commerce/quote-preview")
    public CommercePolicyService.QuoteResult quote(@RequestHeader(name=COMPANY,required=false) Long company,@RequestParam long customerId,
        @RequestParam String country,@RequestParam String currency,@RequestParam int count,@RequestParam(required=false) String shippingOptionCode) {
        long merchant=owner(company);if(merchant!=customerId)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Company quote access is not allowed");
        return commerce.quoteForOrder(merchant,country,currency,count,shippingOptionCode);
    }
    @PostMapping("/commerce/batch-quote-preview")
    public CommercePolicyService.BatchQuoteResult batchQuote(@RequestHeader(name=COMPANY,required=false) Long company,
        @RequestBody CustomerCommercePolicyController.BatchQuoteRequest request) {
        long merchant=owner(company);
        if(request==null||merchant!=request.customerId())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Company quote access is not allowed");
        return commerce.quoteBatch(merchant,request.country(),request.currency(),request.parts(),request.shippingOptionCode());
    }
    @GetMapping("/order-photos")
    public Object unusedPhotos(@RequestHeader(name=COMPANY,required=false) Long company) { return photos.unused(owner(company)); }
    @PostMapping(value="/order-photos",consumes="multipart/form-data")
    public CustomerOrderPhotoService.Photo uploadPhoto(@RequestHeader(name=COMPANY,required=false) Long company,
        @RequestParam("file") org.springframework.web.multipart.MultipartFile file) { return photos.upload(owner(company),file); }
    @DeleteMapping("/order-photos/{photoId}")
    public Map<String,Boolean> removeUnusedPhoto(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable long photoId) {
        photos.removeUnused(owner(company),photoId);return Map.of("removed",true);
    }
    @GetMapping("/order-photos/{photoId}")
    public ResponseEntity<Resource> photo(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable long photoId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").body(photos.readOwned(owner(company),photoId,false));
    }
    @GetMapping("/order-photos/{photoId}/original")
    public ResponseEntity<Resource> original(@RequestHeader(name=COMPANY,required=false) Long company,@PathVariable long photoId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment; filename=card-original")
            .body(photos.readOwned(owner(company),photoId,true));
    }
}
