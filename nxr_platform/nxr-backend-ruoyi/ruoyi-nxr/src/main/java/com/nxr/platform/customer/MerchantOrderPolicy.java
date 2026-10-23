package com.nxr.platform.customer;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Only orders actually linked to their owner's merchant parcel use the B workflow. */
public final class MerchantOrderPolicy {
    private MerchantOrderPolicy() { }
    public static final Set<String> OLD_PAYMENT_STATES = Set.of(
        "terms_confirmation", "payment_expired", "awaiting_payment", "payment_review", "payment_exception");

    public static boolean isBatchOrder(JdbcClient jdbc,long orderId) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM merchant_order_batch_item bi
            JOIN merchant_order_batch b ON b.id=bi.batch_id
            JOIN grading_order o ON o.id=bi.order_id AND o.customer_id=b.merchant_customer_id
            JOIN customer_account c ON c.id=o.customer_id AND c.account_type_code='merchant'
            WHERE o.id=:orderId
            """).param("orderId",orderId).query(Integer.class).single()>0;
    }

    public static String effectiveStatus(String status,String admission,LocalDateTime acceptedAt,String approvedTerms,String acceptedTerms) {
        if(status==null || !OLD_PAYMENT_STATES.contains(status)) return status;
        if(admission!=null && !"approved".equals(admission)) return "admission_review";
        boolean accepted=acceptedAt!=null && approvedTerms!=null && Objects.equals(approvedTerms,acceptedTerms);
        return admission==null || accepted ? "awaiting_inbound" : "terms_confirmation";
    }

    /** Alias is a fixed source-code identifier, never user input. */
    public static String effectiveStatusSql(String alias) {
        validateAlias(alias);
        String o=alias+".";
        return "CASE WHEN "+o+"status_code IN ('terms_confirmation','payment_expired','awaiting_payment','payment_review','payment_exception')"
            +" THEN CASE WHEN "+o+"admission_status_code IS NOT NULL AND "+o+"admission_status_code<>'approved' THEN 'admission_review'"
            +" WHEN "+o+"admission_status_code IS NULL OR ("+o+"terms_accepted_at IS NOT NULL AND "
            +o+"approved_terms_version IS NOT NULL AND "+o+"accepted_terms_version="+o+"approved_terms_version)"
            +" THEN 'awaiting_inbound' ELSE 'terms_confirmation' END ELSE "+o+"status_code END";
    }

    /** An account type alone never turns an individual order into a partner order. */
    public static String isBatchOrderSql(String alias) {
        validateAlias(alias);
        return "EXISTS (SELECT 1 FROM merchant_order_batch_item channel_bi"
            +" JOIN merchant_order_batch channel_b ON channel_b.id=channel_bi.batch_id"
            +" JOIN customer_account channel_c ON channel_c.id=channel_b.merchant_customer_id AND channel_c.account_type_code='merchant'"
            +" WHERE channel_bi.order_id="+alias+".id AND channel_b.merchant_customer_id="+alias+".customer_id)";
    }

    public static String orderChannelSql(String alias) {
        return "CASE WHEN "+isBatchOrderSql(alias)+" THEN 'partner' ELSE 'individual' END";
    }

    public static String visibleStatusSql(String alias) {
        return "CASE WHEN "+isBatchOrderSql(alias)+" THEN "+effectiveStatusSql(alias)+" ELSE "+alias+".status_code END";
    }

    private static void validateAlias(String alias) {
        if(alias==null || !alias.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Invalid internal SQL alias");
    }
}
