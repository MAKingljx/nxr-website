package com.nxr.platform.customer;

import com.nxr.platform.admission.OrderAdmissionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Currency-isolated prepaid wallets for merchant accounts. */
@Service
public class MerchantWalletService {

    public static final Set<String> SUPPORTED_CURRENCIES = Set.of(
        "USD", "CNY", "EUR", "GBP", "HKD", "JPY", "CAD", "AUD", "SGD"
    );
    private static final Set<String> RECHARGE_PROVIDERS = Set.of(
        "manual_transfer", "bank_transfer", "wechat_transfer", "alipay_transfer", "stripe"
    );
    private static final BigDecimal MAX_RECHARGE = new BigDecimal("100000000.00");
    private static final DateTimeFormatter REFERENCE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final JdbcClient jdbcClient;
    private OrderAdmissionService orderAdmissionService;

    public MerchantWalletService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Autowired(required = false)
    public void setOrderAdmissionService(OrderAdmissionService orderAdmissionService) {
        this.orderAdmissionService = orderAdmissionService;
    }

    public void requireMerchant(long customerId) {
        String type = jdbcClient.sql("SELECT account_type_code FROM customer_account WHERE id = :customerId")
            .param("customerId", customerId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer account not found"));
        if (!"merchant".equalsIgnoreCase(type)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant account is required");
        }
    }

    public MerchantProfile merchantProfile(long customerId) {
        requireMerchant(customerId);
        return jdbcClient.sql(
                """
                SELECT customer_id, company_name, contact_name, created_at, updated_at
                FROM merchant_company_profile WHERE customer_id = :customerId
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> new MerchantProfile(
                rs.getLong("customer_id"), rs.getString("company_name"), rs.getString("contact_name"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)
            ))
            .optional()
            .orElse(new MerchantProfile(customerId, "", "", null, null));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MerchantProfile saveMerchantProfile(long customerId, MerchantProfileRequest request) {
        requireMerchant(customerId);
        String companyName = requireText(request == null ? null : request.companyName(), "Company name", 191);
        String contactName = requireText(request.contactName(), "Contact name", 128);
        jdbcClient.sql(
                """
                INSERT INTO merchant_company_profile (customer_id, company_name, contact_name)
                VALUES (:customerId, :companyName, :contactName)
                ON DUPLICATE KEY UPDATE company_name = VALUES(company_name), contact_name = VALUES(contact_name),
                    updated_at = CURRENT_TIMESTAMP
                """
            )
            .param("customerId", customerId)
            .param("companyName", companyName)
            .param("contactName", contactName)
            .update();
        return merchantProfile(customerId);
    }

    public List<WalletBalance> listWallets(long customerId) {
        requireMerchant(customerId);
        return jdbcClient.sql(
                """
                SELECT id, customer_id, currency_code, balance, created_at, updated_at
                FROM merchant_wallet WHERE customer_id = :customerId ORDER BY currency_code
                """
            )
            .param("customerId", customerId)
            .query((rs, rowNum) -> new WalletBalance(
                rs.getLong("id"), rs.getLong("customer_id"), rs.getString("currency_code"),
                rs.getBigDecimal("balance"), rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)
            ))
            .list();
    }

    public WalletTransactionPage listTransactions(long customerId, String requestedCurrency, int requestedPage, int requestedPageSize) {
        requireMerchant(customerId);
        String currency = requestedCurrency == null || requestedCurrency.isBlank() ? "" : normalizeCurrency(requestedCurrency);
        int page = Math.max(1, requestedPage);
        int pageSize = Math.max(1, Math.min(100, requestedPageSize));
        int offset = (page - 1) * pageSize;
        List<WalletTransaction> items = jdbcClient.sql(
                """
                SELECT t.id, t.transaction_no, w.currency_code, t.transaction_type_code, t.direction_code,
                       t.amount, t.balance_after, t.reference_type_code, t.reference_id, t.note,
                       t.actor_type_code, t.created_at
                FROM merchant_wallet_transaction t
                JOIN merchant_wallet w ON w.id = t.wallet_id
                WHERE w.customer_id = :customerId AND (:currency = '' OR w.currency_code = :currency)
                ORDER BY t.created_at DESC, t.id DESC LIMIT :pageSize OFFSET :offset
                """
            )
            .param("customerId", customerId)
            .param("currency", currency)
            .param("pageSize", pageSize)
            .param("offset", offset)
            .query((rs, rowNum) -> mapTransaction(rs))
            .list();
        int total = jdbcClient.sql(
                """
                SELECT COUNT(*) FROM merchant_wallet_transaction t
                JOIN merchant_wallet w ON w.id = t.wallet_id
                WHERE w.customer_id = :customerId AND (:currency = '' OR w.currency_code = :currency)
                """
            )
            .param("customerId", customerId)
            .param("currency", currency)
            .query(Integer.class)
            .single();
        return new WalletTransactionPage(items, page, pageSize, total);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RechargeRecord createRecharge(long customerId, RechargeRequest request) {
        requireMerchant(customerId);
        String currency = normalizeCurrency(request == null ? null : request.currencyCode());
        BigDecimal amount = normalizePositiveAmount(request.amount(), currency, "Recharge amount");
        if (amount.compareTo(MAX_RECHARGE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recharge amount is too large");
        }
        String provider = normalizeProvider(request.providerCode());
        String rechargeNo = generateReference("RCH");
        jdbcClient.sql(
                """
                INSERT INTO merchant_wallet_recharge
                    (recharge_no, customer_id, currency_code, amount, provider_code, payer_reference, proof_reference, status_code)
                VALUES (:rechargeNo, :customerId, :currency, :amount, :provider, :payerReference, :proofReference, 'pending')
                """
            )
            .param("rechargeNo", rechargeNo)
            .param("customerId", customerId)
            .param("currency", currency)
            .param("amount", amount)
            .param("provider", provider)
            .param("payerReference", blankToNull(clean(request.payerReference(), 255)))
            .param("proofReference", blankToNull(clean(request.proofReference(), 512)))
            .update();
        return requireRechargeByNumber(rechargeNo);
    }

    public RechargePage listRecharges(long customerId, String requestedStatus, int requestedPage, int requestedPageSize) {
        requireMerchant(customerId);
        String status = clean(requestedStatus, 32).toLowerCase(Locale.ROOT);
        int page = Math.max(1, requestedPage);
        int pageSize = Math.max(1, Math.min(100, requestedPageSize));
        int offset = (page - 1) * pageSize;
        List<RechargeRecord> items = jdbcClient.sql(
                """
                SELECT id, recharge_no, customer_id, currency_code, amount, provider_code, payer_reference,
                       proof_reference, provider_transaction_id, status_code, reviewed_by_user_id,
                       reviewed_at, review_note, created_at, updated_at
                FROM merchant_wallet_recharge
                WHERE customer_id = :customerId AND (:status = '' OR status_code = :status)
                ORDER BY created_at DESC, id DESC LIMIT :pageSize OFFSET :offset
                """
            )
            .param("customerId", customerId)
            .param("status", status)
            .param("pageSize", pageSize)
            .param("offset", offset)
            .query((rs, rowNum) -> mapRecharge(rs))
            .list();
        int total = jdbcClient.sql(
                "SELECT COUNT(*) FROM merchant_wallet_recharge WHERE customer_id = :customerId AND (:status = '' OR status_code = :status)"
            )
            .param("customerId", customerId)
            .param("status", status)
            .query(Integer.class)
            .single();
        return new RechargePage(items, page, pageSize, total);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RechargeRecord reviewRecharge(
        long customerId, long rechargeId, long adminUserId, boolean approved, RechargeReviewRequest request
    ) {
        requireMerchant(customerId);
        RechargeRecord recharge = lockRecharge(customerId, rechargeId);
        if (!"pending".equals(recharge.statusCode())) {
            if (approved && "confirmed".equals(recharge.statusCode())) {
                String repeatedTransactionId = requireText(
                    request == null ? null : request.providerTransactionId(), "Provider transaction id", 255
                );
                if (!repeatedTransactionId.equals(recharge.providerTransactionId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Recharge was confirmed with another transaction");
                }
                return recharge;
            }
            if (!approved && "rejected".equals(recharge.statusCode())) {
                return recharge;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recharge has already been reviewed");
        }
        String note = requireText(request == null ? null : request.note(), "Review note", 2000);
        if (approved) {
            String providerTransactionId = requireText(request.providerTransactionId(), "Provider transaction id", 255);
            creditRecharge(recharge, "admin", null, adminUserId, providerTransactionId, note);
        } else {
            jdbcClient.sql(
                    """
                    UPDATE merchant_wallet_recharge SET status_code = 'rejected', reviewed_by_user_id = :adminUserId,
                        reviewed_at = CURRENT_TIMESTAMP, review_note = :note, updated_at = CURRENT_TIMESTAMP
                    WHERE id = :rechargeId AND status_code = 'pending'
                    """
                )
                .param("adminUserId", adminUserId)
                .param("note", note)
                .param("rechargeId", rechargeId)
                .update();
        }
        return requireRecharge(customerId, rechargeId);
    }

    /** Server-only hook for a gateway adapter after it has verified the provider callback. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RechargeRecord confirmGatewayRecharge(
        long rechargeId, String provider, String providerTransactionId, BigDecimal amount, String currencyCode
    ) {
        RechargeRecord recharge = lockRechargeById(rechargeId);
        String normalizedProvider = normalizeProvider(provider);
        String transactionId = requireText(providerTransactionId, "Provider transaction id", 255);
        String normalizedCurrency = normalizeCurrency(currencyCode);
        BigDecimal normalizedAmount = normalizePositiveAmount(amount, normalizedCurrency, "Recharge amount");
        if (!recharge.providerCode().equals(normalizedProvider)
            || recharge.amount().compareTo(normalizedAmount) != 0
            || !recharge.currencyCode().equals(normalizedCurrency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gateway recharge details do not match");
        }
        if ("confirmed".equals(recharge.statusCode())) {
            if (transactionId.equals(recharge.providerTransactionId())) {
                return recharge;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recharge was confirmed with another transaction");
        }
        if (!"pending".equals(recharge.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Recharge is not pending");
        }
        creditRecharge(recharge, "payment_gateway", null, null, transactionId, "Verified gateway recharge");
        return requireRecharge(recharge.customerId(), recharge.id());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletOrderPayment debitOrder(long customerId, long orderId, String requestedIdempotencyKey) {
        requireMerchant(customerId);
        String idempotencyKey = requireText(requestedIdempotencyKey, "Idempotency key", 128);
        OrderCharge order = jdbcClient.sql(
                """
                SELECT id, customer_id, status_code, total_amount, currency_code
                FROM grading_order WHERE id = :orderId AND customer_id = :customerId FOR UPDATE
                """
            )
            .param("orderId", orderId)
            .param("customerId", customerId)
            .query((rs, rowNum) -> new OrderCharge(
                rs.getLong("id"), rs.getLong("customer_id"), rs.getString("status_code"),
                rs.getBigDecimal("total_amount"), rs.getString("currency_code")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Grading order not found"));
        WalletOrderPayment existing = findOrderPayment(order.id());
        if (existing != null) {
            if (!existing.idempotencyKey().equals(idempotencyKey)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Order has already been paid from a wallet");
            }
            return existing;
        }
        if (orderAdmissionService != null) {
            orderAdmissionService.requirePaymentAllowed(order.id(), customerId);
        }
        if (!Set.of("awaiting_payment", "payment_review").contains(order.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not waiting for payment");
        }
        ReceivableCharge receivable = jdbcClient.sql(
                """
                SELECT id, status_code, amount, currency_code FROM payment_record
                WHERE order_id = :orderId AND direction_code = 'receivable' AND payment_type_code = 'grading_fee'
                ORDER BY id LIMIT 1 FOR UPDATE
                """
            )
            .param("orderId", order.id())
            .query((rs, rowNum) -> new ReceivableCharge(
                rs.getLong("id"), rs.getString("status_code"), rs.getBigDecimal("amount"), rs.getString("currency_code")
            ))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Order payment record is missing"));
        String currency = normalizeCurrency(order.currencyCode());
        if (receivable.amount().compareTo(order.amount()) != 0
            || !currency.equals(normalizeCurrency(receivable.currencyCode()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order payment amount is inconsistent");
        }
        if (!Set.of("pending", "rejected", "failed").contains(receivable.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "The order already has payment activity that requires financial review");
        }
        boolean activeGatewayAttempts = !jdbcClient.sql(
                """
                SELECT id FROM payment_attempt
                WHERE order_id = :orderId
                  AND status_code IN ('creating', 'created', 'approved', 'pending', 'payer_action_required', 'creation_unknown', 'capture_unknown', 'capturing')
                FOR UPDATE
                """
            )
            .param("orderId", order.id())
            .query(Long.class)
            .list()
            .isEmpty();
        if (activeGatewayAttempts) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An online payment attempt is still active for this order");
        }
        long walletId = ensureAndLockWallet(customerId, currency);
        BigDecimal balance = walletBalance(walletId);
        if (balance.compareTo(order.amount()) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient " + currency + " wallet balance");
        }
        BigDecimal balanceAfter = balance.subtract(order.amount()).setScale(2, RoundingMode.HALF_UP);
        jdbcClient.sql("UPDATE merchant_wallet SET balance = :balance, updated_at = CURRENT_TIMESTAMP WHERE id = :walletId")
            .param("balance", balanceAfter)
            .param("walletId", walletId)
            .update();
        long transactionId = insertTransaction(
            walletId, "order_payment", "debit", order.amount(), balanceAfter, "grading_order", order.id(),
            idempotencyKey, "Wallet payment for grading order", "customer", customerId, null
        );
        jdbcClient.sql(
                """
                INSERT INTO merchant_wallet_order_payment
                    (order_id, wallet_id, debit_transaction_id, idempotency_key, amount, currency_code, status_code)
                VALUES (:orderId, :walletId, :transactionId, :idempotencyKey, :amount, :currency, 'paid')
                """
            )
            .param("orderId", order.id())
            .param("walletId", walletId)
            .param("transactionId", transactionId)
            .param("idempotencyKey", idempotencyKey)
            .param("amount", order.amount())
            .param("currency", currency)
            .update();
        int confirmed = jdbcClient.sql(
                """
                UPDATE payment_record SET provider_code = 'wallet', status_code = 'confirmed',
                    provider_transaction_id = :transactionNo, confirmed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = :paymentId AND status_code IN ('pending', 'rejected', 'failed')
                """
            )
            .param("transactionNo", transactionNumber(transactionId))
            .param("paymentId", receivable.id())
            .update();
        if (confirmed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order payment changed before wallet confirmation");
        }
        return requireOrderPayment(order.id());
    }

    public boolean hasPaidWalletOrder(long orderId) {
        return !jdbcClient.sql(
                "SELECT order_id FROM merchant_wallet_order_payment WHERE order_id = :orderId AND status_code = 'paid' FOR UPDATE"
            )
            .param("orderId", orderId)
            .query(Long.class)
            .list()
            .isEmpty();
    }

    public boolean hasWalletHistory(long customerId) {
        int count = jdbcClient.sql(
                """
                SELECT
                    (SELECT COUNT(*) FROM merchant_wallet_recharge WHERE customer_id = :customerId)
                  + (SELECT COUNT(*) FROM merchant_wallet w JOIN merchant_wallet_transaction t ON t.wallet_id = w.id
                     WHERE w.customer_id = :customerId)
                """
            )
            .param("customerId", customerId)
            .query(Integer.class)
            .single();
        return count > 0;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WalletOrderPayment refundOrder(long orderId, String actorType, Long customerId, Long adminUserId, String note) {
        WalletOrderPayment payment = lockOrderPayment(orderId);
        if (payment == null || "refunded".equals(payment.statusCode())) {
            return payment;
        }
        if (!"paid".equals(payment.statusCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wallet order payment is not refundable");
        }
        BigDecimal balance = walletBalance(payment.walletId());
        BigDecimal balanceAfter = balance.add(payment.amount()).setScale(2, RoundingMode.HALF_UP);
        jdbcClient.sql("UPDATE merchant_wallet SET balance = :balance, updated_at = CURRENT_TIMESTAMP WHERE id = :walletId")
            .param("balance", balanceAfter)
            .param("walletId", payment.walletId())
            .update();
        long refundId = insertTransaction(
            payment.walletId(), "order_refund", "credit", payment.amount(), balanceAfter, "grading_order", orderId,
            "order-refund:" + orderId, clean(note, 2000), actorType, customerId, adminUserId
        );
        int refunded = jdbcClient.sql(
                """
                UPDATE merchant_wallet_order_payment SET status_code = 'refunded', refund_transaction_id = :refundId,
                    refunded_at = CURRENT_TIMESTAMP WHERE order_id = :orderId AND status_code = 'paid'
                """
            )
            .param("refundId", refundId)
            .param("orderId", orderId)
            .update();
        if (refunded != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wallet order payment changed before refund");
        }
        jdbcClient.sql(
                """
                UPDATE payment_record SET status_code = 'refunded', updated_at = CURRENT_TIMESTAMP
                WHERE order_id = :orderId AND provider_code = 'wallet' AND payment_type_code = 'grading_fee'
                """
            )
            .param("orderId", orderId)
            .update();
        return requireOrderPayment(orderId);
    }

    private void creditRecharge(
        RechargeRecord recharge, String actorType, Long actorCustomerId, Long actorAdminId, String providerTransactionId, String note
    ) {
        int duplicateProviderTransaction = jdbcClient.sql(
                """
                SELECT COUNT(*) FROM merchant_wallet_recharge
                WHERE provider_code = :provider AND provider_transaction_id = :transactionId AND id <> :rechargeId
                """
            )
            .param("provider", recharge.providerCode())
            .param("transactionId", providerTransactionId)
            .param("rechargeId", recharge.id())
            .query(Integer.class)
            .single();
        if (duplicateProviderTransaction > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Provider transaction has already funded another recharge");
        }
        long walletId = ensureAndLockWallet(recharge.customerId(), recharge.currencyCode());
        BigDecimal balanceAfter = walletBalance(walletId).add(recharge.amount()).setScale(2, RoundingMode.HALF_UP);
        jdbcClient.sql("UPDATE merchant_wallet SET balance = :balance, updated_at = CURRENT_TIMESTAMP WHERE id = :walletId")
            .param("balance", balanceAfter)
            .param("walletId", walletId)
            .update();
        insertTransaction(
            walletId, "recharge", "credit", recharge.amount(), balanceAfter, "wallet_recharge", recharge.id(),
            "recharge:" + recharge.id(), note, actorType, actorCustomerId, actorAdminId
        );
        try {
            jdbcClient.sql(
                    """
                    UPDATE merchant_wallet_recharge SET status_code = 'confirmed', provider_transaction_id = :transactionId,
                        reviewed_by_user_id = :adminUserId, reviewed_at = CURRENT_TIMESTAMP, review_note = :note,
                        updated_at = CURRENT_TIMESTAMP WHERE id = :rechargeId AND status_code = 'pending'
                    """
                )
                .param("transactionId", providerTransactionId)
                .param("adminUserId", actorAdminId)
                .param("note", note)
                .param("rechargeId", recharge.id())
                .update();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Provider transaction has already funded another recharge", exception);
        }
    }

    private long ensureAndLockWallet(long customerId, String currency) {
        jdbcClient.sql(
                "INSERT INTO merchant_wallet (customer_id, currency_code, balance) VALUES (:customerId, :currency, 0) ON DUPLICATE KEY UPDATE id = id"
            )
            .param("customerId", customerId)
            .param("currency", currency)
            .update();
        return jdbcClient.sql(
                "SELECT id FROM merchant_wallet WHERE customer_id = :customerId AND currency_code = :currency FOR UPDATE"
            )
            .param("customerId", customerId)
            .param("currency", currency)
            .query(Long.class)
            .single();
    }

    private BigDecimal walletBalance(long walletId) {
        return jdbcClient.sql("SELECT balance FROM merchant_wallet WHERE id = :walletId FOR UPDATE")
            .param("walletId", walletId)
            .query(BigDecimal.class)
            .single();
    }

    private long insertTransaction(
        long walletId, String type, String direction, BigDecimal amount, BigDecimal balanceAfter,
        String referenceType, long referenceId, String idempotencyKey, String note,
        String actorType, Long actorCustomerId, Long actorAdminId
    ) {
        String transactionNo = generateReference("WLT");
        jdbcClient.sql(
                """
                INSERT INTO merchant_wallet_transaction
                    (wallet_id, transaction_no, transaction_type_code, direction_code, amount, balance_after,
                     reference_type_code, reference_id, idempotency_key, note, actor_type_code,
                     actor_customer_id, actor_admin_user_id)
                VALUES (:walletId, :transactionNo, :type, :direction, :amount, :balanceAfter,
                        :referenceType, :referenceId, :idempotencyKey, :note, :actorType,
                        :actorCustomerId, :actorAdminId)
                """
            )
            .param("walletId", walletId)
            .param("transactionNo", transactionNo)
            .param("type", type)
            .param("direction", direction)
            .param("amount", amount)
            .param("balanceAfter", balanceAfter)
            .param("referenceType", referenceType)
            .param("referenceId", referenceId)
            .param("idempotencyKey", idempotencyKey)
            .param("note", blankToNull(note))
            .param("actorType", actorType)
            .param("actorCustomerId", actorCustomerId)
            .param("actorAdminId", actorAdminId)
            .update();
        return jdbcClient.sql("SELECT id FROM merchant_wallet_transaction WHERE transaction_no = :transactionNo")
            .param("transactionNo", transactionNo)
            .query(Long.class)
            .single();
    }

    private String transactionNumber(long transactionId) {
        return jdbcClient.sql("SELECT transaction_no FROM merchant_wallet_transaction WHERE id = :transactionId")
            .param("transactionId", transactionId)
            .query(String.class)
            .single();
    }

    private RechargeRecord requireRechargeByNumber(String rechargeNo) {
        return jdbcClient.sql(rechargeSelect() + " WHERE recharge_no = :rechargeNo")
            .param("rechargeNo", rechargeNo)
            .query((rs, rowNum) -> mapRecharge(rs))
            .single();
    }

    private RechargeRecord requireRecharge(long customerId, long rechargeId) {
        return jdbcClient.sql(rechargeSelect() + " WHERE id = :rechargeId AND customer_id = :customerId")
            .param("rechargeId", rechargeId)
            .param("customerId", customerId)
            .query((rs, rowNum) -> mapRecharge(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recharge not found"));
    }

    private RechargeRecord lockRecharge(long customerId, long rechargeId) {
        return jdbcClient.sql(rechargeSelect() + " WHERE id = :rechargeId AND customer_id = :customerId FOR UPDATE")
            .param("rechargeId", rechargeId)
            .param("customerId", customerId)
            .query((rs, rowNum) -> mapRecharge(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recharge not found"));
    }

    private RechargeRecord lockRechargeById(long rechargeId) {
        return jdbcClient.sql(rechargeSelect() + " WHERE id = :rechargeId FOR UPDATE")
            .param("rechargeId", rechargeId)
            .query((rs, rowNum) -> mapRecharge(rs))
            .optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Recharge not found"));
    }

    private static String rechargeSelect() {
        return """
            SELECT id, recharge_no, customer_id, currency_code, amount, provider_code, payer_reference,
                   proof_reference, provider_transaction_id, status_code, reviewed_by_user_id,
                   reviewed_at, review_note, created_at, updated_at
            FROM merchant_wallet_recharge
            """;
    }

    private WalletOrderPayment findOrderPayment(long orderId) {
        return jdbcClient.sql(orderPaymentSelect() + " WHERE order_id = :orderId FOR UPDATE")
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapOrderPayment(rs))
            .optional()
            .orElse(null);
    }

    private WalletOrderPayment requireOrderPayment(long orderId) {
        WalletOrderPayment result = findOrderPayment(orderId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet order payment not found");
        }
        return result;
    }

    private WalletOrderPayment lockOrderPayment(long orderId) {
        return jdbcClient.sql(orderPaymentSelect() + " WHERE order_id = :orderId FOR UPDATE")
            .param("orderId", orderId)
            .query((rs, rowNum) -> mapOrderPayment(rs))
            .optional()
            .orElse(null);
    }

    private static String orderPaymentSelect() {
        return """
            SELECT order_id, wallet_id, debit_transaction_id, refund_transaction_id, idempotency_key,
                   amount, currency_code, status_code, paid_at, refunded_at
            FROM merchant_wallet_order_payment
            """;
    }

    private static RechargeRecord mapRecharge(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RechargeRecord(
            rs.getLong("id"), rs.getString("recharge_no"), rs.getLong("customer_id"),
            rs.getString("currency_code"), rs.getBigDecimal("amount"), rs.getString("provider_code"),
            rs.getString("payer_reference"), rs.getString("proof_reference"), rs.getString("provider_transaction_id"),
            rs.getString("status_code"), rs.getObject("reviewed_by_user_id", Long.class),
            rs.getObject("reviewed_at", LocalDateTime.class), rs.getString("review_note"),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class)
        );
    }

    private static WalletTransaction mapTransaction(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WalletTransaction(
            rs.getLong("id"), rs.getString("transaction_no"), rs.getString("currency_code"),
            rs.getString("transaction_type_code"), rs.getString("direction_code"), rs.getBigDecimal("amount"),
            rs.getBigDecimal("balance_after"), rs.getString("reference_type_code"), rs.getLong("reference_id"),
            rs.getString("note"), rs.getString("actor_type_code"), rs.getObject("created_at", LocalDateTime.class)
        );
    }

    private static WalletOrderPayment mapOrderPayment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WalletOrderPayment(
            rs.getLong("order_id"), rs.getLong("wallet_id"), rs.getLong("debit_transaction_id"),
            rs.getObject("refund_transaction_id", Long.class), rs.getString("idempotency_key"),
            rs.getBigDecimal("amount"), rs.getString("currency_code"), rs.getString("status_code"),
            rs.getObject("paid_at", LocalDateTime.class), rs.getObject("refunded_at", LocalDateTime.class)
        );
    }

    private static String normalizeCurrency(String value) {
        String currency = clean(value, 8).toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported wallet currency");
        }
        return currency;
    }

    private static String normalizeProvider(String value) {
        String provider = clean(value, 32).toLowerCase(Locale.ROOT);
        if (!RECHARGE_PROVIDERS.contains(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported recharge provider");
        }
        return provider;
    }

    private static BigDecimal normalizePositiveAmount(BigDecimal value, String currency, String label) {
        if (value == null || value.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " must be greater than zero");
        }
        int scale = "JPY".equals(currency) ? 0 : 2;
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY).setScale(2);
        } catch (ArithmeticException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " has unsupported precision for " + currency);
        }
    }

    private static String generateReference(String prefix) {
        return prefix + "-" + LocalDateTime.now().format(REFERENCE_TIME) + "-"
            + ThreadLocalRandom.current().nextInt(100000, 1000000);
    }

    private static String requireText(String value, String label, int maxLength) {
        String cleaned = clean(value, maxLength);
        if (cleaned.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + " is required");
        }
        return cleaned;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return "";
        String cleaned = value.trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record OrderCharge(long id, long customerId, String statusCode, BigDecimal amount, String currencyCode) {}
    private record ReceivableCharge(long id, String statusCode, BigDecimal amount, String currencyCode) {}

    public record MerchantProfile(long customerId, String companyName, String contactName, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record MerchantProfileRequest(String companyName, String contactName) {}
    public record WalletBalance(long id, long customerId, String currencyCode, BigDecimal balance, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record WalletTransaction(long id, String transactionNo, String currencyCode, String transactionTypeCode,
                                    String directionCode, BigDecimal amount, BigDecimal balanceAfter,
                                    String referenceTypeCode, long referenceId, String note,
                                    String actorTypeCode, LocalDateTime createdAt) {}
    public record WalletTransactionPage(List<WalletTransaction> items, int page, int pageSize, int total) {}
    public record RechargeRequest(String currencyCode, BigDecimal amount, String providerCode,
                                  String payerReference, String proofReference) {}
    public record RechargeReviewRequest(String providerTransactionId, String note) {}
    public record RechargeRecord(long id, String rechargeNo, long customerId, String currencyCode, BigDecimal amount,
                                 String providerCode, String payerReference, String proofReference,
                                 String providerTransactionId, String statusCode, Long reviewedByUserId,
                                 LocalDateTime reviewedAt, String reviewNote, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record RechargePage(List<RechargeRecord> items, int page, int pageSize, int total) {}
    public record WalletOrderPayment(long orderId, long walletId, long debitTransactionId, Long refundTransactionId,
                                     String idempotencyKey, BigDecimal amount, String currencyCode, String statusCode,
                                     LocalDateTime paidAt, LocalDateTime refundedAt) {}
}
