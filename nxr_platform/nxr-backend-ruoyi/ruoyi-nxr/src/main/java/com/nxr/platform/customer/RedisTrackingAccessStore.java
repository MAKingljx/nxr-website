package com.nxr.platform.customer;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Shared short-lived proof and atomic rate counters; unavailable Redis always fails closed. */
@Service
final class RedisTrackingAccessStore implements TrackingAccessStore {
    private static final String PREFIX="nxr:tracking:v1:";
    private static final DefaultRedisScript<Long> INCREMENT = new DefaultRedisScript<>(
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n",Long.class);
    private final StringRedisTemplate redis;
    RedisTrackingAccessStore(StringRedisTemplate redis){this.redis=redis;}
    @Override public void checkRate(String linkHash,String sourceHash){
        increment(PREFIX+"link:"+linkHash,5);increment(PREFIX+"source:"+sourceHash,30);
    }
    private void increment(String key,int limit){
        try {Long n=redis.execute(INCREMENT,List.of(key),"600");if(n==null)throw unavailable();if(n>limit)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Please try again later");}
        catch(ResponseStatusException e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    @Override public void put(String accessHash,Proof proof){
        try {redis.opsForValue().set(PREFIX+"proof:"+accessHash,proof.linkHash()+":"+(proof.email()?"e":"p")+":"+proof.contactBinding(),Duration.ofSeconds(900));}
        catch(RuntimeException e){throw unavailable();}
    }
    @Override public Proof get(String accessHash){
        try {String value=redis.opsForValue().get(PREFIX+"proof:"+accessHash);if(value==null || !value.matches("[a-f0-9]{64}:[ep]:[a-f0-9]{64}"))return null;return new Proof(value.substring(0,64),value.charAt(65)=='e',value.substring(67));}
        catch(RuntimeException e){throw unavailable();}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order verification is temporarily unavailable");}
}
