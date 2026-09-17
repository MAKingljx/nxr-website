package com.nxr.platform.customer;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import com.nxr.platform.commerce.OrderAccessScopeService;

class EnterpriseCreditServiceTest {
    private JdbcTemplate jdbc;
    private MerchantWalletService wallets;
    private EnterpriseCreditService credits;
    private TransactionTemplate tx;
    @BeforeEach void setup() throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:credit_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=10000");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        try(Connection connection=ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("partner_management_h2.sql"));
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("enterprise_credit_h2.sql"));
        }
        jdbc.execute("CREATE TABLE grading_order(id BIGINT PRIMARY KEY,order_no VARCHAR(50),customer_id BIGINT,status_code VARCHAR(32),total_amount DECIMAL(18,2),currency_code VARCHAR(8))");
        jdbc.execute("CREATE TABLE payment_record(id BIGINT PRIMARY KEY,order_id BIGINT,status_code VARCHAR(32),amount DECIMAL(18,2),currency_code VARCHAR(8),direction_code VARCHAR(32),payment_type_code VARCHAR(32),provider_code VARCHAR(32),provider_transaction_id VARCHAR(100),confirmed_at TIMESTAMP,updated_at TIMESTAMP)");
        var client=JdbcClient.create(jdbc);
        credits=new EnterpriseCreditService(client,new AgentOperatorScopeService(client,new OrderAccessScopeService(client,null)));
        wallets=new MerchantWalletService(client);wallets.setEnterpriseCreditService(credits);
    }
    @Test void defaultsEnableOnlyCnyAndValidatePrecisionAndQuoteVersion() {
        assertThat(credits.adminSettings(1).rates().stream().filter(EnterpriseCreditService.Rate::enabled)).singleElement().satisfies(r->assertThat(r.currencyCode()).isEqualTo("CNY"));
        assertThat(credits.quote(101,new EnterpriseCreditService.QuoteRequest("CNY",bd("1"))).points()).isEqualByComparingTo("1.00");
        assertThatThrownBy(()->credits.quote(101,new EnterpriseCreditService.QuoteRequest("USD",bd("1")))).hasMessageContaining("not configured");
        assertThatThrownBy(()->credits.quote(101,new EnterpriseCreditService.QuoteRequest("PTS",bd("1")))).hasMessageContaining("Unsupported payment currency");
        assertThatThrownBy(()->credits.quote(101,new EnterpriseCreditService.QuoteRequest("CNY",bd("1.001")))).hasMessageContaining("precision");
        assertThatThrownBy(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest("CNY",bd("1"),"manual_transfer",null,null))).hasMessageContaining("Review the current points quote");
        configure("1.005","1");
        assertThat(credits.quote(101,new EnterpriseCreditService.QuoteRequest("CNY",bd("1"))).points()).isEqualByComparingTo("1.01");
        var s=credits.adminSettings(1);
        assertThatThrownBy(()->run(()->credits.saveSettings(1,new EnterpriseCreditService.SettingsRequest(s.version(),bd("1.000000001"),s.rates())))).hasMessageContaining("eight decimal");
    }
    @Test void rechargeSnapshotSurvivesRateChangeAndGatewayRetryWithoutChangingOriginalMoney() {
        configure("1","7.125");long version=version();
        var r=run(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest("USD",bd("10"),"stripe",null,null,version)));
        assertThat(r.creditQuote().points()).isEqualByComparingTo("71.25");configure("2","8");
        assertThatThrownBy(()->run(()->wallets.confirmGatewayRecharge(r.id(),"stripe","provider-1",bd("11"),"USD"))).hasMessageContaining("do not match");
        run(()->wallets.confirmGatewayRecharge(r.id(),"stripe","provider-1",bd("10"),"USD"));
        run(()->wallets.confirmGatewayRecharge(r.id(),"stripe","provider-1",bd("10"),"USD"));
        assertThat(credits.balance(101)).isEqualByComparingTo("71.25");
        assertThat(wallets.listWallets(101)).singleElement().satisfies(w->assertThat(w.currencyCode()).isEqualTo("PTS"));
        assertThat(wallets.listTransactions(101,"PTS",1,20).items()).singleElement().satisfies(t->{
            assertThat(t.sourceCurrency()).isEqualTo("USD");assertThat(t.sourceAmount()).isEqualByComparingTo("10");
            assertThat(t.amount()).isEqualByComparingTo("71.25");assertThat(t.settingsVersion()).isEqualTo(version);
        });
    }
    @Test void orderUsesPointsAndRefundsExactDebitAfterRateChange() {
        configure("1","7.125");credit("CNY","100");order(11,101,"USD","10");long v=version();
        assertThat(credits.orderQuote(101,"ORDER-11").quote().points()).isEqualByComparingTo("71.25");
        assertThatThrownBy(()->run(()->wallets.debitOrder(101,11,"payment-noquote"))).hasMessageContaining("Review the current points quote");
        var paid=run(()->wallets.debitOrder(101,11,"payment-11",v,bd("71.25")));
        assertThat(paid.currencyCode()).isEqualTo("PTS");assertThat(paid.amount()).isEqualByComparingTo("71.25");
        assertThat(credits.balance(101)).isEqualByComparingTo("28.75");
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_record WHERE id=11",BigDecimal.class)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("SELECT currency_code FROM payment_record WHERE id=11",String.class)).isEqualTo("USD");configure("3","9");
        assertThat(run(()->wallets.debitOrder(101,11,"payment-11",v,bd("71.25")))).isEqualTo(paid);
        assertThat(credits.orderQuote(101,"ORDER-11").quote().points()).isEqualByComparingTo("71.25");
        run(()->wallets.refundOrder(11,"admin",null,1L,"Cancelled before shipment"));
        run(()->wallets.refundOrder(11,"admin",null,1L,"Repeated"));
        assertThat(credits.balance(101)).isEqualByComparingTo("100");assertThat(wallets.listTransactions(101,"PTS",1,20).total()).isEqualTo(3);
    }
    @Test void staleQuoteMismatchAndInsufficientBalanceNeverDebitAnyWallet() {
        credit("CNY","10");order(1,101,"CNY","20");long v=version();configure("2","1");
        assertThatThrownBy(()->run(()->wallets.debitOrder(101,1,"stale",v,bd("20")))).hasMessageContaining("settings changed");
        assertThatThrownBy(()->run(()->wallets.debitOrder(101,1,"insufficient",version(),bd("40")))).hasMessageContaining("Insufficient");
        jdbc.update("UPDATE payment_record SET amount=19 WHERE id=1");
        assertThatThrownBy(()->run(()->wallets.debitOrder(101,1,"mismatch",version(),bd("40")))).hasMessageContaining("inconsistent");
        assertThat(credits.balance(101)).isEqualByComparingTo("10");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_wallet_order_payment",Integer.class)).isZero();
    }
    @Test void oldCurrencyBalancesNeedReviewedExactBalancesAndConversionIsIdempotent() {
        configure("1","7");legacy("USD","10");order(1,101,"USD","1");
        assertThatThrownBy(()->run(()->wallets.debitOrder(101,1,"no-bypass",version(),bd("7")))).hasMessageContaining("Insufficient");
        var preview=credits.conversionPreview(1,101);assertThat(preview.totalPoints()).isEqualByComparingTo("70");
        var request=conversion(preview,"conversion-1");jdbc.update("UPDATE merchant_wallet SET balance=11 WHERE customer_id=101 AND currency_code='USD'");
        assertThatThrownBy(()->run(()->credits.convert(1,101,request))).hasMessageContaining("Balances changed");
        jdbc.update("UPDATE merchant_wallet SET balance=10 WHERE customer_id=101 AND currency_code='USD'");
        var converted=run(()->credits.convert(1,101,request));assertThat(run(()->credits.convert(1,101,request))).isEqualTo(converted);
        assertThat(credits.balance(101)).isEqualByComparingTo("70");
        assertThat(credits.summary(101).legacyBalances()).singleElement().satisfies(w->assertThat(w.balance()).isZero());
        assertThat(wallets.listTransactions(101,null,1,20).total()).isEqualTo(2);
    }
    @Test void legacyOrderRefundAfterConversionRemainsVisibleForAnotherExplicitConversion() {
        configure("1","7");legacy("USD","10");order(1,101,"USD","4");
        var oldWallet=new MerchantWalletService(JdbcClient.create(jdbc));run(()->oldWallet.debitOrder(101,1,"old-money"));
        run(()->credits.convert(1,101,conversion(credits.conversionPreview(1,101),"convert-before-refund")));
        assertThat(credits.balance(101)).isEqualByComparingTo("42");run(()->wallets.refundOrder(1,"admin",null,1L,"Old order cancelled"));
        assertThat(credits.summary(101).legacyBalances()).singleElement().satisfies(w->assertThat(w.balance()).isEqualByComparingTo("4"));
        assertThat(credits.balance(101)).isEqualByComparingTo("42");
        run(()->credits.convert(1,101,conversion(credits.conversionPreview(1,101),"convert-returned-money")));
        assertThat(credits.balance(101)).isEqualByComparingTo("70");
    }
    @Test void pendingLegacyRechargeNeedsExplicitPinAndCannotCrossTenant() {
        var oldWallet=new MerchantWalletService(JdbcClient.create(jdbc));
        var r=run(()->oldWallet.createRecharge(101,new MerchantWalletService.RechargeRequest("CNY",bd("5"),"manual_transfer",null,null)));
        assertThatThrownBy(()->run(()->wallets.reviewRecharge(101,r.id(),1,true,new MerchantWalletService.RechargeReviewRequest("cash-1","Verified")))).hasMessageContaining("legacy recharge first");
        assertThatThrownBy(()->run(()->credits.quoteLegacyRecharge(1,202,r.id(),new EnterpriseCreditService.VersionRequest(version())))).hasMessageContaining("pending recharge");
        run(()->credits.quoteLegacyRecharge(1,101,r.id(),new EnterpriseCreditService.VersionRequest(version())));configure("2","1");
        run(()->wallets.reviewRecharge(101,r.id(),1,true,new MerchantWalletService.RechargeReviewRequest("cash-1","Verified")));
        assertThat(credits.balance(101)).isEqualByComparingTo("5");
        assertThatThrownBy(()->credits.summary(303)).hasMessageContaining("enterprise account");
        assertThatThrownBy(()->credits.summary(404)).hasMessageContaining("enterprise account");
        order(1,101,"CNY","1");assertThatThrownBy(()->credits.orderQuote(202,"ORDER-1")).hasMessageContaining("Order not found");
    }
    @Test void staleOperatorOrRevokedPlatformPermissionsCannotConfigureOrApprove() {
        jdbc.update("INSERT INTO agent_operator_binding(sys_user_id,merchant_customer_id,active,created_by_user_id,updated_by_user_id) VALUES(10,101,1,1,1)");jdbc.update("INSERT INTO sys_user_role VALUES(10,1)");
        assertThatThrownBy(()->credits.adminSettings(10)).hasMessageContaining("cannot access platform");
        assertThatThrownBy(()->credits.conversionPreview(10,101)).hasMessageContaining("cannot access platform");
        var r=run(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest("CNY",bd("2"),"manual_transfer",null,null,version())));
        assertThatThrownBy(()->run(()->wallets.reviewRecharge(101,r.id(),10,true,new MerchantWalletService.RechargeReviewRequest("own-transfer","No")))).hasMessageContaining("cannot access platform");
        jdbc.update("INSERT INTO sys_menu VALUES(2150,'nxr:credit:config','0')");jdbc.update("INSERT INTO sys_role_menu VALUES(50,2150)");
        assertThat(credits.adminSettings(70).version()).isEqualTo(1);jdbc.update("DELETE FROM sys_role_menu WHERE role_id=50 AND menu_id=2150");
        assertThatThrownBy(()->credits.adminSettings(70)).hasMessageContaining("Required platform permission");assertThat(credits.balance(101)).isZero();
    }
    @Test void concurrentRechargeAndDifferentOrdersCannotDoubleCreditOrOverspend() throws Exception {
        var r=run(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest("CNY",bd("100"),"manual_transfer",null,null,version())));
        race(()->run(()->wallets.reviewRecharge(101,r.id(),1,true,new MerchantWalletService.RechargeReviewRequest("race-cash","Verified"))),()->run(()->wallets.reviewRecharge(101,r.id(),1,true,new MerchantWalletService.RechargeReviewRequest("race-cash","Verified"))));
        assertThat(credits.balance(101)).isEqualByComparingTo("100");order(1,101,"CNY","80");order(2,101,"CNY","80");
        assertThat(race(()->attempt(()->wallets.debitOrder(101,1,"race-1",1L,bd("80"))),()->attempt(()->wallets.debitOrder(101,2,"race-2",1L,bd("80"))))).containsExactlyInAnyOrder(true,false);
        assertThat(credits.balance(101)).isEqualByComparingTo("20");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_wallet_order_payment",Integer.class)).isEqualTo(1);
    }
    @Test void overflowNeverCreatesAPartialRechargeOrLegacyConversion() {
        configure("1000000000","1000000000");
        long v=version();
        assertThatThrownBy(()->run(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest("USD",bd("100000000"),"manual_transfer",null,null,v)))).hasMessageContaining("supported range");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_wallet_recharge",Integer.class)).isZero();
        legacy("USD","100000000");
        var request=new EnterpriseCreditService.ConversionRequest(v,List.of(new EnterpriseCreditService.LegacyBalance("USD",bd("100000000"))),"overflow","Reviewed");
        assertThatThrownBy(()->run(()->credits.convert(1,101,request))).hasMessageContaining("supported range");
        assertThat(credits.legacyWallets(101)).singleElement().satisfies(w->assertThat(w.balance()).isEqualByComparingTo("100000000"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_credit_conversion",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_wallet_transaction",Integer.class)).isZero();
    }
    @Test void balanceOverflowRollsBackConfirmationAndSnapshotsSerializeDecimalsExactly() throws Exception {
        jdbc.update("INSERT INTO merchant_wallet(customer_id,currency_code,balance) VALUES(101,'PTS',9999999999999999.99)");
        var r=run(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest("CNY",bd("1"),"manual_transfer",null,null,version())));
        assertThatThrownBy(()->run(()->wallets.reviewRecharge(101,r.id(),1,true,new MerchantWalletService.RechargeReviewRequest("overflow-cash","Verified")))).hasMessageContaining("supported range");
        assertThat(wallets.requireRecharge(101,r.id()).statusCode()).isEqualTo("pending");
        assertThat(credits.balance(101)).isEqualByComparingTo("9999999999999999.99");
        String json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r.creditQuote());
        assertThat(json).contains("\"points\":\"1.00\"","\"sourceAmount\":\"1.00\"");
    }
    @Test void changedLegacyOrderAmountRequiresFreshAcknowledgementEvenAtSameExchangeVersion() {
        credit("CNY","100");order(1,101,"CNY","10");
        var shown=credits.orderQuote(101,"ORDER-1").quote();
        assertThatThrownBy(()->run(()->wallets.debitOrder(101,1,"missing-ack",shown.settingsVersion()))).hasMessageContaining("confirm the points amount");
        jdbc.update("UPDATE grading_order SET total_amount=20 WHERE id=1");
        jdbc.update("UPDATE payment_record SET amount=20 WHERE id=1");
        assertThatThrownBy(()->run(()->wallets.debitOrder(101,1,"stale-order",shown.settingsVersion(),shown.points()))).hasMessageContaining("points amount changed");
        assertThat(credits.balance(101)).isEqualByComparingTo("100");
        var fresh=credits.orderQuote(101,"ORDER-1").quote();
        run(()->wallets.debitOrder(101,1,"fresh-order",fresh.settingsVersion(),fresh.points()));
        assertThat(credits.balance(101)).isEqualByComparingTo("80");
    }
    @Test void platformOverviewShowsOnePointsRowIncludingEnterprisesWithoutAWallet() {
        var client=JdbcClient.create(jdbc);var scope=new AgentOperatorScopeService(client,new OrderAccessScopeService(client,null));
        var overview=new AgentOverviewService(client,scope);overview.setEnterpriseCreditService(credits);
        legacy("USD","10");credit("CNY","3");
        var rows=overview.overview(1,"wallet",null,null,1,100).items();
        assertThat(rows).hasSize(3).allSatisfy(row->assertThat(row.currencyCode()).isEqualTo("PTS"));
        assertThat(rows.stream().filter(r->r.companyId()==101).findFirst().orElseThrow().amount()).isEqualByComparingTo("3");
        assertThat(rows.stream().filter(r->r.companyId()==202).findFirst().orElseThrow().amount()).isZero();
    }
    @Test void conversionAndAllFundingPathsShareCustomerFirstLockOrderWithForeignKeys() throws Exception {
        // These are production FK relationships; the minimal partner fixture omits them by default.
        jdbc.execute("ALTER TABLE merchant_wallet ADD CONSTRAINT test_wallet_customer_fk FOREIGN KEY(customer_id) REFERENCES customer_account(id)");
        jdbc.execute("ALTER TABLE merchant_wallet_recharge ADD CONSTRAINT test_recharge_customer_fk FOREIGN KEY(customer_id) REFERENCES customer_account(id)");
        credit("CNY","100");legacy("CNY","1");
        order(1,101,"CNY","10");run(()->wallets.debitOrder(101,1,"before-race",1L,bd("10")));
        order(2,101,"CNY","3");
        var pending=run(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest("CNY",bd("2"),"stripe",null,null,1L)));
        var conversion=conversion(credits.conversionPreview(1,101),"mixed-race-conversion");
        List<Supplier<?>> operations=List.of(
            ()->run(()->credits.convert(1,101,conversion)),
            ()->run(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest("CNY",bd("5"),"manual_transfer",null,null,1L))),
            ()->run(()->wallets.confirmGatewayRecharge(pending.id(),"stripe","mixed-gateway",bd("2"),"CNY")),
            ()->run(()->wallets.debitOrder(101,2,"mixed-debit",1L,bd("3"))),
            ()->run(()->wallets.refundOrder(1,"admin",null,1L,"Mixed refund"))
        );
        var pool=Executors.newFixedThreadPool(operations.size());var start=new CountDownLatch(1);
        try {
            var futures=operations.stream().map(operation->pool.submit(()->{start.await();return operation.get();})).toList();
            start.countDown();for(var future:futures)assertThat(future.get(20,TimeUnit.SECONDS)).isNotNull();
        } finally {pool.shutdownNow();}
        assertThat(credits.balance(101)).isEqualByComparingTo("100");
        assertThat(credits.legacyWallets(101)).singleElement().satisfies(w->assertThat(w.balance()).isZero());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enterprise_credit_conversion",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM merchant_wallet_transaction WHERE transaction_type_code='order_refund'",Integer.class)).isEqualTo(1);
    }
    @Test void everyWalletResponseTransportsAmountsAsExactDecimalStrings() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        credit("CNY","100");legacy("USD","9999999999999999.99");order(1,101,"CNY","1");
        var payment=run(()->wallets.debitOrder(101,1,"json-payment",1L,bd("1")));
        var balance=mapper.valueToTree(wallets.listWallets(101).get(0));
        assertThat(balance.get("balance").isTextual()).isTrue();assertThat(balance.get("balance").asText()).isEqualTo("99.00");
        var transaction=mapper.valueToTree(wallets.listTransactions(101,"PTS",1,20).items().get(0));
        for(String key:List.of("amount","balanceAfter","sourceAmount","points")) assertThat(transaction.get(key).isTextual()).as(key).isTrue();
        assertThat(mapper.valueToTree(payment).get("amount").asText()).isEqualTo("1.00");
        assertThat(mapper.valueToTree(payment).get("amount").isTextual()).isTrue();
        assertThat(mapper.valueToTree(wallets.listRecharges(101,null,1,20).items().get(0)).get("amount").isTextual()).isTrue();
        var legacy=mapper.valueToTree(credits.summary(101)).get("legacyBalances").get(0).get("balance");
        assertThat(legacy.isTextual()).isTrue();assertThat(legacy.asText()).isEqualTo("9999999999999999.99");
    }
    private void configure(String points,String usd){var s=credits.adminSettings(1);var rates=s.rates().stream().map(r->r.currencyCode().equals("USD")?new EnterpriseCreditService.Rate("USD",bd(usd),true):r).toList();run(()->credits.saveSettings(1,new EnterpriseCreditService.SettingsRequest(s.version(),bd(points),rates)));}
    private long version(){return credits.adminSettings(1).version();}
    private void credit(String currency,String amount){var r=run(()->wallets.createRecharge(101,new MerchantWalletService.RechargeRequest(currency,bd(amount),"manual_transfer",null,null,version())));run(()->wallets.reviewRecharge(101,r.id(),1,true,new MerchantWalletService.RechargeReviewRequest("cash-"+r.id(),"Verified")));}
    private void order(long id,long customer,String currency,String amount){jdbc.update("INSERT INTO grading_order VALUES(?,?,?,?,?,?)",id,"ORDER-"+id,customer,"awaiting_payment",bd(amount),currency);jdbc.update("INSERT INTO payment_record(id,order_id,status_code,amount,currency_code,direction_code,payment_type_code) VALUES(?,?,'pending',?,?,'receivable','grading_fee')",id,id,bd(amount),currency);}
    private void legacy(String currency,String amount){jdbc.update("INSERT INTO merchant_wallet(customer_id,currency_code,balance) VALUES(101,?,?)",currency,bd(amount));}
    private EnterpriseCreditService.ConversionRequest conversion(EnterpriseCreditService.ConversionPreview p,String key){return new EnterpriseCreditService.ConversionRequest(p.settingsVersion(),p.balances().stream().map(b->new EnterpriseCreditService.LegacyBalance(b.currencyCode(),b.balance())).toList(),key,"Reviewed conversion");}
    private boolean attempt(Supplier<?> work){try{run(work);return true;}catch(ResponseStatusException e){assertThat(e.getMessage()).contains("Insufficient");return false;}}
    private <T>T run(Supplier<T> work){return tx.execute(s->work.get());}
    private static BigDecimal bd(String value){return new BigDecimal(value);}
    private static List<Object> race(Supplier<?> a,Supplier<?> b)throws Exception{var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var one=pool.submit(()->{start.await();return a.get();});var two=pool.submit(()->{start.await();return b.get();});start.countDown();return List.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
}
