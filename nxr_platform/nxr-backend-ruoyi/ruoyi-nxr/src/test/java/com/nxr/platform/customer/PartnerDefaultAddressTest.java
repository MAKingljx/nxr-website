package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class PartnerDefaultAddressTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OrderFulfillmentService service;

    @BeforeEach void setup() throws Exception {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:partner_address_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        try(Connection connection=ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("order_fulfillment_h2.sql"));
        }
        // Mirror the production MySQL column's automatic timestamp behavior.
        jdbc.execute("ALTER TABLE customer_address ALTER COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        service=new OrderFulfillmentService(JdbcClient.create(jdbc),jdbc);
        jdbc.update("INSERT INTO customer_account(id,email,password_hash,display_name,account_type_code) VALUES(1,'one@test','x','One','merchant'),(2,'two@test','x','Two','merchant'),(3,'three@test','x','Three','customer')");
        jdbc.update("INSERT INTO customer_address(id,customer_id,label,contact_name,contact_phone,address_line1,city,postal_code,country,is_default) VALUES(10,1,'Home','One','123','First street','City','1','US',1),(11,1,'Office','One','456','Second street','City','2','US',0),(20,2,'Foreign','Two','789','Foreign street','Town','3','CA',1),(30,3,'Personal','Three','345','Personal street','Town','4','FR',1)");
        jdbc.update("INSERT INTO grading_order(id,order_no,customer_id,status_code,service_level_code,total_card_count,service_fee,return_shipping_fee,total_amount,currency_code,contact_name,contact_phone,return_address_line1,return_city,return_postal_code,return_country) VALUES(1,'ORIGINAL',1,'grading','basic_grading',1,10,0,10,'USD','One','123','First street','City','1','US')");
    }

    @Test void onlyDefaultFlagsChangeAndHistoricalOrderAddressIsPreserved() {
        var orderBefore=jdbc.queryForMap("SELECT * FROM grading_order WHERE id=1");
        var addressesBefore=jdbc.queryForList("SELECT id,label,contact_name,contact_phone,address_line1,address_line2,city,region,postal_code,country,created_at,updated_at FROM customer_address ORDER BY id");
        var selected=tx.execute(status->service.setMerchantDefaultAddress(1,11));
        assertThat(selected.defaultAddress()).isTrue();assertThat(selected.id()).isEqualTo(11);
        assertThat(jdbc.queryForList("SELECT id FROM customer_address WHERE customer_id=1 AND is_default=1",Long.class)).containsExactly(11L);
        assertThat(jdbc.queryForMap("SELECT * FROM grading_order WHERE id=1")).isEqualTo(orderBefore);
        assertThat(jdbc.queryForList("SELECT id,label,contact_name,contact_phone,address_line1,address_line2,city,region,postal_code,country,created_at,updated_at FROM customer_address ORDER BY id")).isEqualTo(addressesBefore);
        assertThat(service.requireAddress(2,20).defaultAddress()).isTrue();
    }

    @Test void foreignMissingAndIndividualRequestsMakeNoChanges() {
        var before=jdbc.queryForList("SELECT * FROM customer_address ORDER BY id");
        assertThatThrownBy(()->tx.execute(s->service.setMerchantDefaultAddress(1,20))).hasMessageContaining("404");
        assertThatThrownBy(()->tx.execute(s->service.setMerchantDefaultAddress(1,999))).hasMessageContaining("404");
        assertThatThrownBy(()->tx.execute(s->service.setMerchantDefaultAddress(3,30))).hasMessageContaining("403");
        assertThat(jdbc.queryForList("SELECT * FROM customer_address ORDER BY id")).isEqualTo(before);
    }

    @Test void concurrentSelectionsFinishWithExactlyOneDefault() throws Exception {
        var executor=Executors.newFixedThreadPool(2);
        try {
            var first=executor.submit(()->tx.execute(s->service.setMerchantDefaultAddress(1,10)));
            var second=executor.submit(()->tx.execute(s->service.setMerchantDefaultAddress(1,11)));
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_address WHERE customer_id=1 AND is_default=1",Integer.class)).isOne();
        assertThat(jdbc.queryForList("SELECT address_line1 FROM customer_address WHERE customer_id=1 ORDER BY id",String.class)).isEqualTo(List.of("First street","Second street"));
    }
}
