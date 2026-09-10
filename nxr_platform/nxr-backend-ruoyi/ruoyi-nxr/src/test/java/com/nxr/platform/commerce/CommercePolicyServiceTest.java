package com.nxr.platform.commerce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class CommercePolicyServiceTest {

    private JdbcClient jdbc;
    private CommercePolicyService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:commerce_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        JdbcTemplate template = new JdbcTemplate(dataSource);
        jdbc = JdbcClient.create(template);
        template.execute("CREATE TABLE customer_account(id BIGINT PRIMARY KEY,email VARCHAR(255),account_type_code VARCHAR(32),is_active TINYINT)");
        template.execute("CREATE TABLE grading_service_price(id BIGINT AUTO_INCREMENT PRIMARY KEY,price_code VARCHAR(32),currency_code VARCHAR(3),unit_price DECIMAL(18,2),is_active TINYINT)");
        template.execute("CREATE TABLE return_shipping_option(id BIGINT AUTO_INCREMENT PRIMARY KEY,option_code VARCHAR(32),display_name VARCHAR(128),country_scope VARCHAR(255),currency_code VARCHAR(3),price_amount DECIMAL(18,2),sort_order INT,is_active TINYINT)");
        template.execute("CREATE TABLE commerce_price_policy(id BIGINT AUTO_INCREMENT PRIMARY KEY,policy_code VARCHAR(64),display_name VARCHAR(128),customer_segment_code VARCHAR(16),customer_id BIGINT,currency_code VARCHAR(3),minimum_quantity INT,maximum_quantity INT,unit_price DECIMAL(18,2),priority_no INT,is_active TINYINT)");
        template.execute("CREATE TABLE commerce_shipping_policy(id BIGINT AUTO_INCREMENT PRIMARY KEY,policy_code VARCHAR(64),display_name VARCHAR(128),destination_country VARCHAR(128),currency_code VARCHAR(3),per_card_weight_grams INT,packaging_weight_grams INT,first_weight_grams INT,first_weight_price DECIMAL(18,2),additional_weight_grams INT,additional_weight_price DECIMAL(18,2),discount_quantity_threshold INT,discount_percent DECIMAL(5,2),free_shipping_quantity_threshold INT,priority_no INT,is_active TINYINT)");
        template.execute("CREATE TABLE commerce_business_line(id BIGINT AUTO_INCREMENT PRIMARY KEY,line_code VARCHAR(48),display_name VARCHAR(128),order_origin_code VARCHAR(32),is_default TINYINT,is_active TINYINT)");
        template.execute("CREATE TABLE commerce_work_center(id BIGINT AUTO_INCREMENT PRIMARY KEY,center_code VARCHAR(48),display_name VARCHAR(128),is_default TINYINT,is_active TINYINT)");
        template.execute("CREATE TABLE commerce_customer_routing(customer_id BIGINT PRIMARY KEY,business_line_id BIGINT,work_center_id BIGINT,order_origin_code VARCHAR(32),updated_by_user_id BIGINT,updated_at TIMESTAMP)");
        jdbc.sql("INSERT INTO customer_account(id,email,account_type_code,is_active) VALUES(1,'collector@example.test','collector',1),(2,'merchant@example.test','merchant',1)").update();
        jdbc.sql("INSERT INTO grading_service_price(price_code,currency_code,unit_price,is_active) VALUES('basic_grading','USD',7.00,1)").update();
        jdbc.sql("INSERT INTO return_shipping_option(option_code,display_name,country_scope,currency_code,price_amount,sort_order,is_active) VALUES('tracked','Tracked','*','USD',3.00,1,1)").update();
        service = new CommercePolicyService(jdbc);
    }

    @Test
    void appliesSpecificBusinessPriceAndDeterministicWeightShipping() {
        jdbc.sql("INSERT INTO commerce_price_policy(policy_code,display_name,customer_segment_code,customer_id,currency_code,minimum_quantity,maximum_quantity,unit_price,priority_no,is_active) VALUES('business','Business','business',NULL,'USD',1,NULL,5.00,0,1),('specific','Specific','business',2,'USD',1,NULL,4.25,0,1)").update();
        jdbc.sql("INSERT INTO commerce_shipping_policy(policy_code,display_name,destination_country,currency_code,per_card_weight_grams,packaging_weight_grams,first_weight_grams,first_weight_price,additional_weight_grams,additional_weight_price,discount_quantity_threshold,discount_percent,free_shipping_quantity_threshold,priority_no,is_active) VALUES('us-weight','US weight','US','USD',80,120,300,5.00,100,1.50,4,25.00,10,0,1)").update();

        CommercePolicyService.QuoteResult quote = service.quoteForOrder(2, "us", "usd", 4);

        assertEquals(new BigDecimal("4.25"), quote.unitPrice());
        assertEquals(new BigDecimal("17.00"), quote.serviceFee());
        assertEquals(new BigDecimal("6.00"), quote.returnShippingFee());
        assertEquals(new BigDecimal("23.00"), quote.totalAmount());
        assertEquals(440, quote.totalWeightGrams());
        assertEquals(440, quote.chargeableWeightGrams());
        assertEquals("customer_policy", quote.pricingSourceCode());
        assertEquals("weight_policy", quote.shippingSourceCode());
        assertEquals("weight_1", quote.shippingOptionCode());

        CommercePolicyService.QuoteResult selectedWeight = service.quoteForOrder(2, "US", "USD", 4, quote.shippingOptionCode());
        assertEquals(quote.returnShippingFee(), selectedWeight.returnShippingFee());
        CommercePolicyService.QuoteResult selectedLegacy = service.quoteForOrder(2, "US", "USD", 4, "tracked");
        assertEquals("global_shipping", selectedLegacy.shippingSourceCode());
        assertEquals(new BigDecimal("3.00"), selectedLegacy.returnShippingFee());

        CommercePolicyService.QuoteResult free = service.quoteForOrder(2, "US", "USD", 10);
        assertEquals(new BigDecimal("0.00"), free.returnShippingFee());
    }

    @Test
    void fallsBackToExistingGlobalPriceAndSelectedShippingOption() {
        CommercePolicyService.QuoteResult quote = service.quoteForOrder(1, "CA", "USD", 2, "tracked");

        assertEquals(new BigDecimal("7.00"), quote.unitPrice());
        assertEquals(new BigDecimal("3.00"), quote.returnShippingFee());
        assertEquals("global_price", quote.pricingSourceCode());
        assertEquals("global_shipping", quote.shippingSourceCode());
        assertEquals("tracked", quote.shippingOptionCode());
        assertNull(quote.totalWeightGrams());
    }

    @Test
    void customerSpecificPolicyRequiresActiveBusinessCustomer() {
        CommercePolicyService.PricePolicyRequest request = new CommercePolicyService.PricePolicyRequest(
            null, "collector-special", "Collector special", "consumer", 1L, "USD", 1, null,
            new BigDecimal("2.00"), 0, true
        );
        assertThrows(ResponseStatusException.class, () -> service.savePricePolicy(request));
    }

    @Test
    void batchQuoteChargesOneShipmentAndAllocatesEveryMinorUnit() {
        jdbc.sql("UPDATE return_shipping_option SET price_amount=3.01 WHERE option_code='tracked'").update();

        CommercePolicyService.BatchQuoteResult batch = service.quoteBatch(1, "US", "USD", List.of(
            new CommercePolicyService.BatchPartRequest("child-a", 1),
            new CommercePolicyService.BatchPartRequest("child-b", 2)
        ), "tracked");

        assertEquals(new BigDecimal("3.01"), batch.aggregate().returnShippingFee());
        assertEquals(new BigDecimal("1.00"), batch.allocations().get(0).returnShippingFee());
        assertEquals(new BigDecimal("2.01"), batch.allocations().get(1).returnShippingFee());
        assertEquals(batch.aggregate().totalAmount(), batch.allocations().stream()
            .map(CommercePolicyService.AllocatedBatchQuote::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void customerRoutingCannotClaimAnOwnedInventoryBusinessLine() {
        jdbc.sql("INSERT INTO commerce_business_line(id,line_code,display_name,order_origin_code,is_default,is_active) VALUES(10,'customer','Customer','customer_submission',1,1),(11,'owned','Owned','owned_inventory',1,1)").update();
        jdbc.sql("INSERT INTO commerce_work_center(id,center_code,display_name,is_default,is_active) VALUES(20,'center','Center',1,1)").update();

        CommercePolicyService.CustomerRouting saved = service.saveCustomerRouting(
            new CommercePolicyService.CustomerRoutingRequest(1L, 10L, 20L, "customer_submission"), 7L
        );
        assertEquals("customer_submission", saved.orderOriginCode());
        assertThrows(ResponseStatusException.class, () -> service.saveCustomerRouting(
            new CommercePolicyService.CustomerRoutingRequest(1L, 11L, 20L, "owned_inventory"), 7L
        ));
    }
}
