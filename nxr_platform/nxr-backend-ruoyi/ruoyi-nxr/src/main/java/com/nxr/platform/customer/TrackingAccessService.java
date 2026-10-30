package com.nxr.platform.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Short-lived proof bound to one active link. Contact values are never retained. */
@Service
public class TrackingAccessService {
    private final JdbcClient jdbc;
    private final TrackingAccessStore store;
    private final SecureRandom random=new SecureRandom();
    @Autowired public TrackingAccessService(JdbcClient jdbc,TrackingAccessStore store){this.jdbc=jdbc;this.store=store;}
    public record VerifyRequest(String token,String contact) {}
    public record Verified(String accessToken,long expiresInSeconds) {}
    private record Contact(String phone,String email) {}

    public Verified verify(VerifyRequest request,String remoteAddress){
        String token=request==null?null:request.token(),contact=request==null?null:request.contact();
        String linkHash=validToken(token)?hash(token):hash("invalid");
        store.checkRate(linkHash,hash(remoteAddress==null?"unknown":remoteAddress));
        if(!validToken(token) || contact==null || contact.length()>320 || contact.isBlank())throw unauthorized();
        Contact saved=contactForLink(linkHash);
        String supplied=contact.contains("@")?email(contact):phone(contact);
        String expected=saved==null?"":contact.contains("@")?email(saved.email()):phone(saved.phone());
        if(supplied.isEmpty() || expected.isEmpty() || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8)))throw unauthorized();
        byte[] bytes=new byte[32];random.nextBytes(bytes);String access=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // The unknown random proof itself salts the contact digest; neither contact nor proof is stored.
        store.put(hash(access),new TrackingAccessStore.Proof(linkHash,contact.contains("@"),hash(expected+"\u0000"+linkHash+"\u0000"+access)));
        return new Verified(access,900);
    }
    public void requireAccess(String token,String access){
        if(!validToken(token)||!validToken(access))throw unauthorized();
        TrackingAccessStore.Proof session=store.get(hash(access));
        String linkHash=hash(token);
        if(session==null || !MessageDigest.isEqual(session.linkHash().getBytes(StandardCharsets.US_ASCII),linkHash.getBytes(StandardCharsets.US_ASCII)))throw unauthorized();
        Contact current=contactForLink(linkHash);if(current==null)throw unauthorized();
        String normalized=session.email()?email(current.email()):phone(current.phone());
        if(normalized.isEmpty() || !MessageDigest.isEqual(session.contactBinding().getBytes(StandardCharsets.US_ASCII),hash(normalized+"\u0000"+linkHash+"\u0000"+access).getBytes(StandardCharsets.US_ASCII)))throw unauthorized();
    }
    private Contact contactForLink(String linkHash){
        var current=jdbc.sql("""
            SELECT CASE WHEN i.partner_order_version>0 THEN i.client_phone_snapshot ELSE c.phone END AS phone,
                   CASE WHEN i.partner_order_version>0 THEN i.client_email_snapshot ELSE c.email END AS email
            FROM agent_order_tracking_token t JOIN agent_intake i ON i.id=t.intake_id AND i.merchant_customer_id=t.merchant_customer_id
            JOIN agent_client c ON c.id=i.client_id AND c.merchant_customer_id=i.merchant_customer_id
            LEFT JOIN grading_order o ON o.id=i.order_id AND o.customer_id=i.merchant_customer_id
            WHERE t.token_hash=:hash AND t.status_code='active' AND t.revoked_at IS NULL
              AND i.status_code<>'cancelled' AND (i.order_id IS NULL OR (o.id IS NOT NULL AND o.status_code<>'cancelled'))
              AND (i.order_id IS NOT NULL OR NOT EXISTS (
                SELECT 1 FROM merchant_order_batch_item bi JOIN merchant_order_batch b ON b.id=bi.batch_id
                JOIN grading_order old_order ON old_order.id=bi.order_id AND old_order.customer_id=i.merchant_customer_id
                WHERE b.merchant_customer_id=i.merchant_customer_id AND old_order.status_code='cancelled'
                  AND (CAST(bi.intake_no AS CHAR(128))=CAST(i.intake_no AS CHAR(128)) OR (bi.intake_no IS NULL AND CAST(bi.client_reference AS CHAR(128))=CAST(i.intake_no AS CHAR(128))))
              ))
            """).param("hash",linkHash).query((rs,n)->new Contact(rs.getString("phone"),rs.getString("email"))).optional();
        if(current.isPresent())return current.get();
        return jdbc.sql("""
            SELECT CASE WHEN i.partner_order_version>0 THEN i.client_phone_snapshot ELSE c.phone END AS phone,
                   CASE WHEN i.partner_order_version>0 THEN i.client_email_snapshot ELSE c.email END AS email
            FROM merchant_batch_tracking_token t JOIN merchant_order_batch_item bi ON bi.id=t.batch_item_id
            JOIN merchant_order_batch b ON b.id=bi.batch_id
            JOIN grading_order o ON o.id=bi.order_id AND o.customer_id=b.merchant_customer_id
            JOIN agent_intake i ON i.order_id=o.id AND i.merchant_customer_id=o.customer_id
            JOIN agent_client c ON c.id=i.client_id AND c.merchant_customer_id=i.merchant_customer_id
            WHERE t.token_hash=:hash AND t.status_code='active' AND t.revoked_at IS NULL AND o.status_code<>'cancelled' AND i.status_code<>'cancelled'
            """).param("hash",linkHash).query((rs,n)->new Contact(rs.getString("phone"),rs.getString("email"))).optional().orElse(null);
    }
    private static boolean validToken(String value){return value!=null && value.matches("[A-Za-z0-9_-]{43}");}
    private static String email(String s){return s==null?"":s.replaceAll("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$","").toLowerCase(Locale.ROOT);}
    private static String phone(String s){
        if(s==null || !s.matches("[+0-9()\\-\\s\\p{Z}]+"))return "";
        String normalized=s.replaceAll("[()\\-\\s\\p{Z}]+","");
        return normalized.matches("\\+?[0-9]+")?normalized:"";
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static ResponseStatusException unauthorized(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Unable to verify this order");}
}
