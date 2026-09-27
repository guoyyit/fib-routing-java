package com.github.guoyyit.fibroutingjava.model;

/** IP 协议版本及其地址位宽。 */
public enum IpVersion {
    V4(32),
    V6(128);

    private final int maxPrefixLength;

    IpVersion(int maxPrefixLength) {
        this.maxPrefixLength = maxPrefixLength;
    }

    public int maxPrefixLength() {
        return maxPrefixLength;
    }
}
