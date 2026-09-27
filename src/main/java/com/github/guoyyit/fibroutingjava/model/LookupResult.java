package com.github.guoyyit.fibroutingjava.model;

/** Result of one lookup; fields other than outcome may be null. */
public record LookupResult(
        LookupOutcome outcome,
        IpPrefix matchedPrefix,
        String source,
        NextHop nextHop,
        long version) {

    public static LookupResult forwarded(IpPrefix prefix, String source, NextHop hop, long version) {
        return new LookupResult(LookupOutcome.FORWARDED, prefix, source, hop, version);
    }

    public static LookupResult unreachable(long version) {
        return new LookupResult(LookupOutcome.UNREACHABLE, null, null, null, version);
    }

    public static LookupResult timeout(long version) {
        return new LookupResult(LookupOutcome.TIMEOUT, null, null, null, version);
    }
}
