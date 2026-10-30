package com.nxr.platform.customer;

interface TrackingAccessStore {
    record Proof(String linkHash,boolean email,String contactBinding) {}
    void checkRate(String linkHash,String sourceHash);
    void put(String accessHash,Proof proof);
    Proof get(String accessHash);
}
