package com.github.guoyyit.fibroutingjava.model;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * 不可变的 IP 网络前缀（CIDR），同时支持 IPv4 / IPv6。
 * 内部地址字节始终按协议版本规整为 4 或 16 字节，并按前缀长度掩码清零主机位。
 */
public final class IpPrefix {

    private final IpVersion version;
    private final byte[] address;
    private final int prefixLength;

    public IpPrefix(IpVersion version, byte[] address, int prefixLength) {
        this.version = Objects.requireNonNull(version, "version");
        Objects.requireNonNull(address, "address");
        if (address.length != version.maxPrefixLength() / 8) {
            throw new IllegalArgumentException(
                    "address byte length " + address.length + " does not match " + version);
        }
        if (prefixLength < 0 || prefixLength > version.maxPrefixLength()) {
            throw new IllegalArgumentException(
                    "prefix length " + prefixLength + " out of range for " + version);
        }
        this.address = mask(address.clone(), prefixLength);
        this.prefixLength = prefixLength;
    }

    /**
     * 解析 "10.0.0.0/8" 或 "2001:db8::/32" 形式的 CIDR；主机位会被掩码清零。
     * 省略 "/长度" 时按地址族取主机路由长度（v4=32，v6=128）。
     */
    public static IpPrefix parse(String cidr) {
        Objects.requireNonNull(cidr, "cidr");
        String trimmed = cidr.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty prefix");
        }
        int slash = trimmed.indexOf('/');
        String addrPart = slash >= 0 ? trimmed.substring(0, slash) : trimmed;
        int len;
        if (slash >= 0) {
            try {
                len = Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid prefix length: " + cidr, e);
            }
        } else {
            len = -1;
        }
        InetAddress inet = parseAddress(addrPart, cidr);
        IpVersion ipVersion;
        byte[] bytes;
        if (inet instanceof Inet4Address) {
            ipVersion = IpVersion.V4;
            bytes = inet.getAddress();
        } else if (inet instanceof Inet6Address) {
            ipVersion = IpVersion.V6;
            bytes = inet.getAddress();
            if (isV4Mapped(bytes)) {
                // 统一拒绝 IPv4 映射写法，避免同一地址出现两种匹配键
                throw new IllegalArgumentException("IPv4-mapped IPv6 address is not allowed: " + cidr);
            }
        } else {
            throw new IllegalArgumentException("unsupported address type: " + cidr);
        }
        int prefixLength = len < 0 ? ipVersion.maxPrefixLength() : len;
        return new IpPrefix(ipVersion, bytes, prefixLength);
    }

    /** 仅解析主机地址（用于目的地址查找），返回规整后的字节。 */
    public static byte[] parseDestination(String ip) {
        InetAddress inet = parseAddress(ip, ip);
        if (inet instanceof Inet4Address) {
            return inet.getAddress();
        }
        if (inet instanceof Inet6Address addr6) {
            byte[] bytes = addr6.getAddress();
            if (isV4Mapped(bytes)) {
                // 目的地址同样不允许 v4-mapped 写法
                throw new IllegalArgumentException("IPv4-mapped IPv6 address is not allowed: " + ip);
            }
            return bytes;
        }
        throw new IllegalArgumentException("unsupported address: " + ip);
    }

    /** 根据地址字节推断协议版本。 */
    public static IpVersion versionOf(byte[] destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.length == 4) {
            return IpVersion.V4;
        }
        if (destination.length == 16) {
            return IpVersion.V6;
        }
        throw new IllegalArgumentException("invalid destination byte length: " + destination.length);
    }

    private static InetAddress parseAddress(String addrPart, String original) {
        try {
            return InetAddress.getByName(addrPart);
        } catch (UnknownHostException e) {
            // getByName 对字面量失败即视为非法；不触发 DNS（IP 字面量不会查询）
            throw new IllegalArgumentException("invalid IP address: " + original, e);
        }
    }

    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == (byte) 0xff && b[11] == (byte) 0xff;
    }

    private static byte[] mask(byte[] bytes, int prefixLength) {
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < bytes.length; i++) {
            if (i < fullBytes) {
                continue;
            }
            if (i == fullBytes) {
                int remaining = prefixLength % 8;
                if (remaining != 0) {
                    bytes[i] = (byte) (bytes[i] & (0xff << (8 - remaining)));
                    continue;
                }
            }
            bytes[i] = 0;
        }
        return bytes;
    }

    public IpVersion version() {
        return version;
    }

    public byte[] addressBytes() {
        return address.clone();
    }

    public int prefixLength() {
        return prefixLength;
    }

    /** 判断给定目的地址是否落在此前缀内（版本不一致直接不命中）。 */
    public boolean contains(byte[] destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.length != address.length) {
            return false;
        }
        int fullBytes = prefixLength / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != destination[i]) {
                return false;
            }
        }
        int remaining = prefixLength % 8;
        if (remaining != 0) {
            byte maskByte = (byte) (0xff << (8 - remaining));
            return (address[fullBytes] & maskByte) == (destination[fullBytes] & maskByte);
        }
        return true;
    }

    /** 此前缀是否在地址空间上包含 other（要求同版本，长度不大于 other）。 */
    public boolean containsPrefix(IpPrefix other) {
        if (version != other.version) {
            return false;
        }
        return prefixLength <= other.prefixLength && contains(other.address);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IpPrefix other)) {
            return false;
        }
        return prefixLength == other.prefixLength
                && version == other.version
                && Arrays.equals(address, other.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, prefixLength, Arrays.hashCode(address));
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(address).getHostAddress() + "/" + prefixLength;
        } catch (UnknownHostException e) {
            return version + ":" + prefixLength;
        }
    }
}
