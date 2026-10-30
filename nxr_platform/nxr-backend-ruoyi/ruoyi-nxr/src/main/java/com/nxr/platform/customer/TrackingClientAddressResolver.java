package com.nxr.platform.customer;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Forwarded addresses are trusted only from explicitly configured append-only proxies. */
@Component
final class TrackingClientAddressResolver {
    @Value("${nxr.tracking.trusted-proxies:}") private String trustedProxies="";
    String source(HttpServletRequest request){
        String peer=numericAddress(request.getRemoteAddr());
        if(peer==null) return "unknown";
        Set<String> trusted=Arrays.stream(trustedProxies.split(",")).map(String::trim).map(TrackingClientAddressResolver::numericAddress).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if(!trusted.contains(peer))return peer;
        String header=request.getHeader("X-Forwarded-For");
        if(header==null || header.isBlank() || header.length()>2048)return peer;
        String[] chain=header.split(",",-1);if(chain.length>32)return peer;
        for(int i=chain.length-1;i>=0;i--){String hop=numericAddress(chain[i].trim());if(hop==null)return peer;if(!trusted.contains(hop))return hop;}
        return peer;
    }
    private static String numericAddress(String raw){
        if(raw==null || raw.isBlank())return null;String value=raw.trim();
        if(value.indexOf(':')>=0){if(!value.matches("[0-9A-Fa-f:.]+"))return null;}
        else {if(!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return null;for(String part:value.split("\\."))if(Integer.parseInt(part)>255)return null;}
        try{return InetAddress.getByName(value).getHostAddress();}catch(Exception ignored){return null;}
    }
}
