package com.github.guoyyit.fibroutingjava.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Immutable classless prefix (CIDR), for example {@code 10.0.0.0/8} or
 * {@code 2001:db8::/32}. The host bits are always masked off so the same
 * network always has the same textual form and identity.
 */
public final class IpPrefix {

    private final IpAddress network;
    private final int prefixLength;

    private IpPrefix(IpAddress network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /** Parses {@code address/len}; accepts host bits and masks them off. */
    @JsonCreator
    public static IpPrefix parse(String cidr) {
        Objects.requireNonNull(cidr, "prefix");
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("prefix must be in CIDR form address/len: " + cidr);
        }
        IpAddress address = IpAddress.parse(cidr.substring(0, slash));
        String lenText = cidr.substring(slash + 1).trim();
        if (lenText.isEmpty()) {
            throw new IllegalArgumentException("missing prefix length: " + cidr);
        }
        for (int i = 0; i < lenText.length(); i++) {
            char c = lenText.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("illegal prefix length: " + cidr);
            }
        }
        int len = Integer.parseInt(lenText);
        int max = address.isIpv4() ? 32 : 128;
        if (len < 0 || len > max) {
            throw new IllegalArgumentException(
                    "prefix length " + len + " out of range for address family: " + cidr);
        }
        return new IpPrefix(mask(address, len), len);
    }

    /** Wraps an already-known address as a /32 or /128 host prefix. */
    public static IpPrefix hostRoute(IpAddress address) {
        int len = address.isIpv4() ? 32 : 128;
        return new IpPrefix(address, len);
    }

    private static IpAddress mask(IpAddress address, int len) {
        byte[] raw = address.copyBytes();
        int totalBits = raw.length * 8;
        for (int bit = len; bit < totalBits; bit++) {
            int byteIndex = bit >> 3;
            raw[byteIndex] &= (byte) ~(0x80 >> (bit & 7));
        }
        return IpAddress.fromBytes(raw);
    }

    public IpAddress networkAddress() {
        return network;
    }

    public int prefixLength() {
        return prefixLength;
    }

    public boolean isIpv4() {
        return network.isIpv4();
    }

    /** True when the given destination address falls inside this prefix. */
    public boolean contains(IpAddress address) {
        Objects.requireNonNull(address, "address");
        if (address.byteLength() != network.byteLength()) {
            return false;
        }
        return commonPrefixBits(network.copyBytes(), address.copyBytes()) >= prefixLength;
    }

    /**
     * True when {@code other} is fully contained within this prefix, including
     * equality. Mixed families never contain each other.
     */
    public boolean contains(IpPrefix other) {
        Objects.requireNonNull(other, "other");
        if (other.network.byteLength() != network.byteLength()) {
            return false;
        }
        if (other.prefixLength < prefixLength) {
            return false;
        }
        return commonPrefixBits(network.copyBytes(), other.network.copyBytes()) >= prefixLength;
    }

    private static int commonPrefixBits(byte[] a, byte[] b) {
        int total = a.length * 8;
        int common = 0;
        for (int i = 0; i < total; i++) {
            int ai = a[i >> 3] & (0x80 >> (i & 7));
            int bi = b[i >> 3] & (0x80 >> (i & 7));
            if (ai != bi) {
                return common;
            }
            common++;
        }
        return common;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IpPrefix other)) {
            return false;
        }
        return prefixLength == other.prefixLength && network.equals(other.network);
    }

    @Override
    public int hashCode() {
        return Objects.hash(network, prefixLength);
    }

    @JsonValue
    @Override
    public String toString() {
        return network.canonicalText() + "/" + prefixLength;
    }
}
