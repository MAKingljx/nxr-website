package com.nxr.platform.customer;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Exchange rules value cash once; immutable snapshots keep later settlement and refunds stable. */
@Service
public class EnterpriseCreditService {
    public static final String UNIT = "PTS";
    private static final BigDecimal MAX_VALUE = new BigDecimal("9999999999999999.99");
    private final JdbcClient jdbc;
    private final AgentOperatorScopeService scope;

    public EnterpriseCreditService(JdbcClient jdbc, AgentOperatorScopeService scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    public record Rate(String currencyCode, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cnyPerUnit, boolean enabled) { }
    public record Settings(long version, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pointsPerCny, int pointScale, List<Rate> rates, LocalDateTime updatedAt) { }
    public record SettingsRequest(Long expectedVersion, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pointsPerCny, List<Rate> rates) { }
    public record QuoteRequest(String currencyCode, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount) { }
    public record VersionRequest(Long settingsVersion) { }
    public record Quote(long settingsVersion, String sourceCurrency, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal sourceAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal cnyPerUnit, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pointsPerCny, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal points, String unitCode) { }
    public record Summary(String unitCode, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance, long settingsVersion, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pointsPerCny,
        List<Rate> rates, List<MerchantWalletService.WalletBalance> legacyBalances) { }
    public record OrderQuote(Quote quote, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance, boolean sufficient) { }
    public record LegacyBalance(String currencyCode, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance) { }
    public record ConversionLine(String currencyCode, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal points) { }
    public record ConversionPreview(long settingsVersion, List<ConversionLine> balances, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalPoints) { }
    public record ConversionRequest(Long expectedVersion, List<LegacyBalance> balances, String idempotencyKey, String note) { }
    public record ConversionResult(long id, long customerId, long settingsVersion, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal points, LocalDateTime createdAt) { }
    private record OrderSource(long id, String currencyCode, BigDecimal amount) { }
    private record Config(long version, BigDecimal pointsPerCny, LocalDateTime updatedAt) { }
    private record PreviousConversion(long id, String requestFingerprint, long settingsVersion, BigDecimal points, LocalDateTime createdAt) { }

    public void requireFinance(long actor) { scope.requirePlatformPermissions(actor, "nxr:customer:finance"); }
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Settings adminSettings(long actor) {
        scope.requirePlatformPermissions(actor, "nxr:credit:config");
        return settings(false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Settings saveSettings(long actor, SettingsRequest request) {
        scope.requirePlatformPermissions(actor, "nxr:credit:config");
        Settings current = settings(true);
        expectVersion(request == null ? null : request.expectedVersion(), current.version());
        BigDecimal pointsPerCny = positiveRate(request.pointsPerCny(), "Points per CNY");
        if (request.rates() == null || request.rates().size() != MerchantWalletService.SUPPORTED_CURRENCIES.size())
            throw bad("Provide the conversion settings for each currency");
        Map<String, Rate> normalized = new LinkedHashMap<>();
        for (Rate rate : request.rates()) {
            if (rate == null) throw bad("Currency settings are required");
            String code = currency(rate.currencyCode());
            if (normalized.containsKey(code)) throw bad("Each currency can appear only once");
            BigDecimal value = rate.cnyPerUnit() == null && !rate.enabled() ? null : positiveRate(rate.cnyPerUnit(), "CNY per unit");
            if ("CNY".equals(code) && (!rate.enabled() || value == null || value.compareTo(BigDecimal.ONE) != 0))
                throw bad("CNY must remain enabled at one CNY per unit");
            normalized.put(code, new Rate(code, value, rate.enabled()));
        }
        jdbc.sql("UPDATE enterprise_credit_config SET version=version+1,points_per_cny=:points,updated_by_user_id=:actor,updated_at=CURRENT_TIMESTAMP WHERE id=1")
            .params(params("points", pointsPerCny, "actor", actor)).update();
        for (Rate rate : normalized.values()) {
            jdbc.sql("UPDATE enterprise_credit_rate SET cny_per_unit=:rate,enabled=:enabled WHERE currency_code=:currency")
                .params(params("rate", rate.cnyPerUnit(), "enabled", rate.enabled(), "currency", rate.currencyCode())).update();
        }
        return settings(false);
    }

    // Hold the one configuration row for a consistent version/rate set in every quote or update transaction.
    private Settings settings(boolean lock) {
        Config config = jdbc.sql("SELECT version,points_per_cny,updated_at FROM enterprise_credit_config WHERE id=1" + (lock ? " FOR UPDATE" : ""))
            .query(Config.class).single();
        List<Rate> rates = jdbc.sql("SELECT currency_code,cny_per_unit,enabled FROM enterprise_credit_rate ORDER BY currency_code")
            .query(Rate.class).list();
        return new Settings(config.version(), config.pointsPerCny(), 2, rates, config.updatedAt());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public Summary summary(long customer) {
        requireMerchant(customer);
        Settings settings = settings(false);
        return new Summary(UNIT, balance(customer), settings.version(), settings.pointsPerCny(), settings.rates(), legacyWallets(customer));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Quote quote(long customer, QuoteRequest request) {
        requireMerchant(customer);
        if (request == null) throw bad("Enter an amount and currency");
        return calculate(settings(true), request.currencyCode(), request.amount());
    }

    public Quote prepare(String currency, BigDecimal amount, Long expectedVersion) {
        Settings settings = settings(true);
        expectVersion(expectedVersion, settings.version());
        return calculate(settings, currency, amount);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OrderQuote orderQuote(long customer, String orderNo) {
        requireMerchant(customer);
        var source = jdbc.sql("SELECT id,currency_code, total_amount AS amount FROM grading_order WHERE customer_id=:customer AND order_no=:order")
            .params(params("customer", customer, "order", orderNo)).query(OrderSource.class).optional()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        Quote quote = snapshot("grading_order",source.id());
        if(quote==null) quote = calculate(settings(true), source.currencyCode(), source.amount());
        BigDecimal balance = balance(customer);
        return new OrderQuote(quote, balance, balance.compareTo(quote.points()) >= 0);
    }

    private Quote calculate(Settings settings, String requestedCurrency, BigDecimal requestedAmount) {
        String currency = currency(requestedCurrency);
        BigDecimal amount = cashAmount(currency, requestedAmount);
        Rate rate = settings.rates().stream().filter(r -> r.currencyCode().equals(currency)).findFirst().orElse(null);
        if (rate == null || !rate.enabled() || rate.cnyPerUnit() == null)
            throw conflict("Conversion for " + currency + " is not configured. Contact NXR to enable it.");
        BigDecimal points = checked(amount.multiply(rate.cnyPerUnit()).multiply(settings.pointsPerCny()).setScale(2, RoundingMode.HALF_UP));
        if (points.signum() <= 0) throw bad("This amount is too small to convert into points");
        return new Quote(settings.version(), currency, amount, rate.cnyPerUnit(), settings.pointsPerCny(), points, UNIT);
    }

    public Quote snapshot(String type, long reference) {
        return jdbc.sql("SELECT settings_version,source_currency,source_amount,cny_per_unit,points_per_cny,points,'PTS' AS unit_code FROM enterprise_credit_snapshot WHERE reference_type_code=:type AND reference_id=:id")
            .params(params("type", type, "id", reference)).query(Quote.class).optional().orElse(null);
    }

    public void saveSnapshot(long customer, String type, long reference, Quote quote) {
        jdbc.sql("""
            INSERT INTO enterprise_credit_snapshot(reference_type_code,reference_id,customer_id,settings_version,
                source_currency,source_amount,cny_per_unit,points_per_cny,points)
            VALUES(:type,:id,:customer,:version,:currency,:amount,:rate,:pointsPerCny,:points)
            """).params(params("type",type,"id",reference,"customer",customer,"version",quote.settingsVersion(),
                "currency",quote.sourceCurrency(),"amount",quote.sourceAmount(),"rate",quote.cnyPerUnit(),
                "pointsPerCny",quote.pointsPerCny(),"points",quote.points())).update();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Quote quoteLegacyRecharge(long actor, long customer, long rechargeId, VersionRequest request) {
        requireFinance(actor);
        requireMerchant(customer);
        var recharge = jdbc.sql("SELECT currency_code,amount FROM merchant_wallet_recharge WHERE id=:id AND customer_id=:customer AND status_code='pending' FOR UPDATE")
            .params(params("id",rechargeId,"customer",customer)).query(QuoteRequest.class).optional()
            .orElseThrow(() -> conflict("Only a pending recharge can be quoted"));
        Quote old = snapshot("wallet_recharge",rechargeId);
        if (old != null) return old;
        jdbc.sql("SELECT id FROM customer_account WHERE id=:customer FOR UPDATE").param("customer",customer).query(Long.class).single();
        Quote quote = prepare(recharge.currencyCode(),recharge.amount(),request == null ? null : request.settingsVersion());
        saveSnapshot(customer,"wallet_recharge",rechargeId,quote);
        return quote;
    }

    public BigDecimal balance(long customer) {
        return jdbc.sql("SELECT balance FROM merchant_wallet WHERE customer_id=:customer AND currency_code='PTS'")
            .param("customer",customer).query(BigDecimal.class).optional().orElse(new BigDecimal("0.00"));
    }

    public List<MerchantWalletService.WalletBalance> legacyWallets(long customer) {
        return jdbc.sql("SELECT id,customer_id,currency_code,balance,created_at,updated_at FROM merchant_wallet WHERE customer_id=:customer AND currency_code<>'PTS' ORDER BY currency_code")
            .param("customer",customer).query(MerchantWalletService.WalletBalance.class).list();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConversionPreview conversionPreview(long actor,long customer) {
        requireFinance(actor); requireMerchant(customer);
        return preview(settings(true), legacyWallets(customer));
    }

    private ConversionPreview preview(Settings settings,List<MerchantWalletService.WalletBalance> wallets) {
        List<ConversionLine> lines = new ArrayList<>();
        BigDecimal total = new BigDecimal("0.00");
        for (var wallet : wallets) {
            if (wallet.balance().signum() == 0) continue;
            Quote quote = calculate(settings,wallet.currencyCode(),wallet.balance());
            lines.add(new ConversionLine(wallet.currencyCode(),wallet.balance(),quote.points()));
            total = checked(total.add(quote.points()));
        }
        return new ConversionPreview(settings.version(),lines,total);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConversionResult convert(long actor,long customer,ConversionRequest request) {
        requireFinance(actor); requireMerchant(customer);
        if (request == null || request.balances() == null) throw bad("Review the legacy balances first");
        String key = text(request.idempotencyKey(),128,"Request key"), note = text(request.note(),2000,"Conversion note");
        List<LegacyBalance> expected = request.balances().stream().map(b -> {
            if(b == null) throw bad("A legacy balance is missing");
            return new LegacyBalance(currency(b.currencyCode()),cashAmount(currency(b.currencyCode()),b.balance()));
        }).sorted(Comparator.comparing(LegacyBalance::currencyCode)).toList();
        if(expected.stream().map(LegacyBalance::currencyCode).distinct().count()!=expected.size()) throw bad("Each currency can appear only once");
        String fingerprint = fingerprint(request.expectedVersion()+"|"+expected+"|"+note);
        // Serialize all conversion requests for this company, including empty/new-wallet cases.
        jdbc.sql("SELECT id FROM customer_account WHERE id=:customer FOR UPDATE").param("customer",customer).query(Long.class).single();
        var old=jdbc.sql("SELECT id,request_fingerprint,settings_version,points,created_at FROM enterprise_credit_conversion WHERE customer_id=:customer AND idempotency_key=:key")
            .params(params("customer",customer,"key",key)).query(PreviousConversion.class).optional().orElse(null);
        if(old!=null) {
            if(!old.requestFingerprint().equals(fingerprint)) throw conflict("This request was already used for another conversion");
            return new ConversionResult(old.id(),customer,old.settingsVersion(),old.points(),old.createdAt());
        }
        Settings settings=settings(true); expectVersion(request.expectedVersion(),settings.version());
        List<MerchantWalletService.WalletBalance> locked=jdbc.sql("SELECT id,customer_id,currency_code,balance,created_at,updated_at FROM merchant_wallet WHERE customer_id=:customer ORDER BY id FOR UPDATE")
            .param("customer",customer).query(MerchantWalletService.WalletBalance.class).list();
        List<MerchantWalletService.WalletBalance> legacy=locked.stream().filter(w->!UNIT.equals(w.currencyCode())&&w.balance().signum()!=0).toList();
        ConversionPreview preview=preview(settings,legacy);
        List<LegacyBalance> actual=legacy.stream().map(w->new LegacyBalance(w.currencyCode(),w.balance().setScale(2))).sorted(Comparator.comparing(LegacyBalance::currencyCode)).toList();
        if(!expected.equals(actual)) throw conflict("Balances changed. Review the conversion again.");
        if(actual.isEmpty()) throw conflict("There are no legacy balances to convert");
        long target=lockPointWallet(customer);
        BigDecimal result=checked(balance(customer).add(preview.totalPoints()));
        jdbc.sql("UPDATE merchant_wallet SET balance=:balance,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .params(params("balance",result,"id",target)).update();
        jdbc.sql("INSERT INTO enterprise_credit_conversion(customer_id,idempotency_key,request_fingerprint,settings_version,points,actor_user_id,note) VALUES(:customer,:key,:fingerprint,:version,:points,:actor,:note)")
            .params(params("customer",customer,"key",key,"fingerprint",fingerprint,"version",settings.version(),"points",preview.totalPoints(),"actor",actor,"note",note)).update();
        long conversionId=jdbc.sql("SELECT id FROM enterprise_credit_conversion WHERE customer_id=:customer AND idempotency_key=:key")
            .params(params("customer",customer,"key",key)).query(Long.class).single();
        BigDecimal running=result.subtract(preview.totalPoints());
        for(var wallet:legacy) {
            Quote quote=calculate(settings,wallet.currencyCode(),wallet.balance());
            jdbc.sql("UPDATE merchant_wallet SET balance=0,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",wallet.id()).update();
            long source=ledger(wallet.id(),"debit",wallet.balance(),BigDecimal.ZERO,"legacy_conversion_source",conversionId,"convert:"+conversionId,note,actor);
            saveSnapshot(customer,"legacy_conversion",source,quote);
            running=running.add(quote.points());
            ledger(target,"credit",quote.points(),running,"legacy_conversion",source,"convert:"+conversionId+":"+wallet.id(),note,actor);
        }
        return jdbc.sql("SELECT id,customer_id,settings_version,points,created_at FROM enterprise_credit_conversion WHERE id=:id")
            .param("id",conversionId).query(ConversionResult.class).single();
    }

    private long lockPointWallet(long customer) {
        jdbc.sql("INSERT INTO merchant_wallet(customer_id,currency_code,balance) VALUES(:customer,'PTS',0) ON DUPLICATE KEY UPDATE id=id").param("customer",customer).update();
        return jdbc.sql("SELECT id FROM merchant_wallet WHERE customer_id=:customer AND currency_code='PTS' FOR UPDATE").param("customer",customer).query(Long.class).single();
    }

    private long ledger(long wallet,String direction,BigDecimal amount,BigDecimal after,String ref,long reference,String key,String note,long actor) {
        String no="CRD-"+UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO merchant_wallet_transaction(wallet_id,transaction_no,transaction_type_code,direction_code,amount,balance_after,
              reference_type_code,reference_id,idempotency_key,note,actor_type_code,actor_admin_user_id)
            VALUES(:wallet,:no,'legacy_conversion',:direction,:amount,:after,:ref,:reference,:key,:note,'admin',:actor)
            """).params(params("wallet",wallet,"no",no,"direction",direction,"amount",amount,"after",after,
                "ref",ref,"reference",reference,"key",key,"note",note,"actor",actor)).update();
        return jdbc.sql("SELECT id FROM merchant_wallet_transaction WHERE transaction_no=:no").param("no",no).query(Long.class).single();
    }

    private void requireMerchant(long customer) {
        if(jdbc.sql("SELECT COUNT(*) FROM customer_account WHERE id=:id AND account_type_code='merchant' AND is_active=1").param("id",customer).query(Integer.class).single()!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"An active enterprise account is required");
    }
    private static BigDecimal cashAmount(String currency,BigDecimal value) {
        if(value==null||value.signum()<=0) throw bad("Amount must be greater than zero");
        try {return checked(value.setScale("JPY".equals(currency)?0:2,RoundingMode.UNNECESSARY).setScale(2));}
        catch(ArithmeticException e){throw bad("Amount has unsupported precision for "+currency);}
    }
    private static BigDecimal positiveRate(BigDecimal value,String name) {
        if(value==null||value.signum()<=0||value.compareTo(new BigDecimal("1000000000"))>0) throw bad(name+" must be greater than zero and no more than 1000000000");
        try{return value.setScale(8,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw bad(name+" supports up to eight decimal places");}
    }
    public static BigDecimal checked(BigDecimal amount) {
        if(amount.signum()<0||amount.compareTo(MAX_VALUE)>0) throw bad("Credit amount exceeds the supported range");
        return amount;
    }
    private static void expectVersion(Long expected,long current) {
        if(expected==null) throw conflict("Review the current points quote before continuing");
        if(expected!=current) throw conflict("Conversion settings changed. Refresh the quote and try again.");
    }
    private static String currency(String value) {
        String result=value==null?"":value.trim().toUpperCase(Locale.ROOT);
        if(!MerchantWalletService.SUPPORTED_CURRENCIES.contains(result))throw bad("Unsupported payment currency");
        return result;
    }
    private static String text(String value,int max,String label) {
        if(value==null||value.isBlank()||value.trim().length()>max)throw bad(label+" is required and must be no longer than "+max+" characters");
        return value.trim();
    }
    private static String fingerprint(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static Map<String,Object> params(Object... values) {
        Map<String,Object> p=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)p.put((String)values[i],values[i+1]);return p;
    }
    private static ResponseStatusException bad(String value){return new ResponseStatusException(HttpStatus.BAD_REQUEST,value);}
    private static ResponseStatusException conflict(String value){return new ResponseStatusException(HttpStatus.CONFLICT,value);}
}
