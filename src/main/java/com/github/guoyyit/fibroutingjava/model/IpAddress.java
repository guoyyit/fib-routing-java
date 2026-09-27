package com.github.guoyyit.fibroutingjava.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable IPv4 or IPv6 address.
 *
 * <p>Backed by the native byte length (4 or 16); the two families are never
 * mixed. Parsing is self contained and never performs DNS resolution.
 */
public final class IpAddress {

    private final byte[] bytes;

    private IpAddress(byte[] bytes) {
        if (bytes.length != 4 && bytes.length != 16) {
            throw new IllegalArgumentException("address must be 4 or 16 bytes: " + bytes.length);
        }
        this.bytes = bytes;
    }

    /** Factory for callers that already hold a validated 4- or 16-byte array. */
    public static IpAddress fromBytes(byte[] bytes) {
        return new IpAddress(bytes.clone());
    }

    /** Parses a textual IPv4 dotted-quad or RFC 4291 IPv6 address (no zone ids). */
    public static IpAddress parse(String text) {
        Objects.requireNonNull(text, "address");
        String s = text.trim();
        if (s.isEmpty() || s.indexOf('%') >= 0) {
            throw new IllegalArgumentException("malformed IP address: " + text);
        }
        return s.indexOf(':') >= 0 ? parseIpv6(s, text) : parseIpv4(s, text);
    }

    private static IpAddress parseIpv4(String s, String original) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("malformed IPv4 address: " + original);
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                throw new IllegalArgumentException("malformed IPv4 octet: " + original);
            }
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException("malformed IPv4 octet: " + original);
                }
            }
            int v = Integer.parseInt(p);
            if (v > 255) {
                throw new IllegalArgumentException("IPv4 octet out of range: " + original);
            }
            out[i] = (byte) v;
        }
        return new IpAddress(out);
    }

    private static IpAddress parseIpv6(String s, String original) {
        int firstDoubleColon = s.indexOf("::");
        if (firstDoubleColon >= 0 && s.indexOf("::", firstDoubleColon + 1) >= 0) {
            throw new IllegalArgumentException("multiple '::' in IPv6 address: " + original);
        }

        List<String> headSegments = new ArrayList<>();
        List<String> tailSegments = new ArrayList<>();
        boolean compressed = firstDoubleColon >= 0;
        if (compressed) {
            String left = s.substring(0, firstDoubleColon);
            String right = s.substring(firstDoubleColon + 2);
            if (!left.isEmpty()) {
                headSegments.addAll(splitNonEmpty(left, original));
            }
            if (!right.isEmpty()) {
                tailSegments.addAll(splitNonEmpty(right, original));
            }
        } else {
            headSegments.addAll(splitNonEmpty(s, original));
        }

        // An embedded dotted-quad may only appear as the final segment overall.
        List<String> all = new ArrayList<>(headSegments);
        all.addAll(tailSegments);
        boolean embeddedV4 = !all.isEmpty() && all.get(all.size() - 1).indexOf('.') >= 0;
        if (!embeddedV4) {
            for (String seg : all) {
                if (seg.indexOf('.') >= 0) {
                    throw new IllegalArgumentException("embedded IPv4 must be last: " + original);
                }
            }
        }

        int hextetsProvided = all.size() - (embeddedV4 ? 1 : 0) + (embeddedV4 ? 2 : 0);
        if (compressed) {
            if (hextetsProvided >= 8) {
                throw new IllegalArgumentException("'::' must compress at least one zero group: " + original);
            }
            int gap = 8 - hextetsProvided;
            return buildCompressedIpv6(headSegments, tailSegments, embeddedV4, gap, original);
        }
        if (hextetsProvided != 8) {
            throw new IllegalArgumentException("IPv6 address needs 8 groups: " + original);
        }

        int[] hextets = new int[8];
        int pos = 0;
        for (int i = 0; i < all.size(); i++) {
            String seg = all.get(i);
            boolean isV4 = embeddedV4 && i == all.size() - 1;
            if (isV4) {
                byte[] v4 = parseIpv4(seg, original).bytes;
                hextets[pos++] = ((v4[0] & 0xFF) << 8) | (v4[1] & 0xFF);
                hextets[pos++] = ((v4[2] & 0xFF) << 8) | (v4[3] & 0xFF);
            } else {
                hextets[pos++] = parseHextet(seg, original);
            }
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2] = (byte) (hextets[i] >> 8);
            out[i * 2 + 1] = (byte) hextets[i];
        }
        return new IpAddress(out);
    }

    private static IpAddress buildCompressedIpv6(List<String> head, List<String> tail,
                                                 boolean embeddedV4InTail, int gap, String original) {
        int[] values = new int[8];
        int p = 0;
        for (String seg : head) {
            p = writeSegment(seg, false, values, p, original);
        }
        p += gap;
        for (int i = 0; i < tail.size(); i++) {
            boolean isV4 = embeddedV4InTail && i == tail.size() - 1;
            p = writeSegment(tail.get(i), isV4, values, p, original);
        }
        if (p != 8) {
            throw new IllegalArgumentException("malformed IPv6 address: " + original);
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2] = (byte) (values[i] >> 8);
            out[i * 2 + 1] = (byte) values[i];
        }
        return new IpAddress(out);
    }

    private static int writeSegment(String seg, boolean isV4, int[] values, int p, String original) {
        if (isV4) {
            byte[] v4 = parseIpv4(seg, original).bytes;
            values[p++] = ((v4[0] & 0xFF) << 8) | (v4[1] & 0xFF);
            values[p++] = ((v4[2] & 0xFF) << 8) | (v4[3] & 0xFF);
        } else {
            values[p++] = parseHextet(seg, original);
        }
        return p;
    }

    private static List<String> splitNonEmpty(String group, String original) {
        if (group.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("malformed IPv6 address: " + original);
        }
        String[] parts = group.split(":", -1);
        for (String p : parts) {
            if (p.isEmpty()) {
                throw new IllegalArgumentException("empty IPv6 group: " + original);
            }
        }
        return Arrays.asList(parts);
    }

    private static int parseHextet(String seg, String original) {
        if (seg.isEmpty() || seg.length() > 4) {
            throw new IllegalArgumentException("malformed IPv6 group: " + original);
        }
        int v = 0;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            int d = Character.digit(c, 16);
            if (d < 0) {
                throw new IllegalArgumentException("malformed IPv6 group: " + original);
            }
            v = (v << 4) | d;
        }
        return v;
    }

    /** True for IPv4, false for IPv6. */
    public boolean isIpv4() {
        return bytes.length == 4;
    }

    /** Byte width of the native representation: 4 or 16. */
    public int byteLength() {
        return bytes.length;
    }

    /** Unsigned value of the byte at the given index. */
    public int getUnsignedByte(int index) {
        return bytes[index] & 0xFF;
    }

    /** Value of the address bit at the given index, MSB first. */
    public boolean bitAt(int bitIndex) {
        return (bytes[bitIndex >> 3] & (0x80 >> (bitIndex & 7))) != 0;
    }

    /** Defensive copy of the raw address bytes. */
    public byte[] copyBytes() {
        return bytes.clone();
    }

    /** Canonical text: dotted quad for IPv4; RFC 5952 for IPv6. */
    public String canonicalText() {
        if (isIpv4()) {
            return (bytes[0] & 0xFF) + "." + (bytes[1] & 0xFF) + "."
                    + (bytes[2] & 0xFF) + "." + (bytes[3] & 0xFF);
        }
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) {
            groups[i] = ((bytes[i * 2] & 0xFF) << 8) | (bytes[i * 2 + 1] & 0xFF);
        }
        // Find the longest run of zero groups (length >= 2); ties pick the first.
        int bestStart = -1;
        int bestLen = 0;
        int runStart = -1;
        for (int i = 0; i < 8; i++) {
            if (groups[i] == 0) {
                if (runStart < 0) {
                    runStart = i;
                }
                int runLen = i - runStart + 1;
                if (runLen > bestLen) {
                    bestLen = runLen;
                    bestStart = runStart;
                }
            } else {
                runStart = -1;
            }
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < 8) {
            if (bestStart >= 0 && i == bestStart) {
                sb.append("::");
                i += bestLen;
                continue;
            }
            if (i > 0 && sb.charAt(sb.length() - 1) != ':') {
                sb.append(':');
            }
            sb.append(Integer.toHexString(groups[i]));
            i++;
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IpAddress other)) {
            return false;
        }
        return Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
