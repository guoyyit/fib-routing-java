package com.github.guoyyit.fibroutingjava.persist;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.engine.FibSnapshot;
import com.github.guoyyit.fibroutingjava.model.IpAddress;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Atomic, corruption-safe persistence for the single committed FIB snapshot.
 *
 * <p>Every commit is serialized to a temp file in the same directory and then
 * moved over the state file with an atomic rename, so the state file is always
 * either the previous good version or the new complete one - never a torn mix.
 * A SHA-256 over the payload lets a corrupted or partially written file be
 * detected on boot: it is ignored (and quarantined) rather than allowed to
 * pollute the committed in-memory state.
 */
@Component
public final class FibStore {

    private static final Logger log = LoggerFactory.getLogger(FibStore.class);

    private final ObjectMapper objectMapper;
    private final FibProperties properties;

    public FibStore(ObjectMapper objectMapper, FibProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** JSON envelope carrying the version, payload checksum and routes payload. */
    public record Envelope(long version, String payloadSha256, List<PersistedRoute> routes) {
        public Envelope {
            routes = routes == null ? List.of() : List.copyOf(routes);
        }
    }

    /** JSON-friendly flat route shape. */
    public record PersistedRoute(
            String prefix,
            String source,
            @JsonProperty("adminDistance") int adminDistance,
            List<PersistedHop> nextHops) {
    }

    public record PersistedHop(String id, String address, int weight, boolean enabled) {
    }

    /** Atomically persists the snapshot; throws if durable state could not be written. */
    public void persist(FibSnapshot snapshot) {
        try {
            List<PersistedRoute> routes = new ArrayList<>();
            for (RouteEntry route : snapshot.flatRoutes()) {
                List<PersistedHop> hops = new ArrayList<>();
                for (NextHop hop : route.nextHops()) {
                    hops.add(new PersistedHop(hop.id(), hop.address().canonicalText(),
                            hop.weight(), hop.enabled()));
                }
                routes.add(new PersistedRoute(route.prefix().toString(), route.source(),
                        route.adminDistance(), hops));
            }
            byte[] payload = objectMapper.writeValueAsBytes(routes);
            String checksum = sha256(payload);
            Envelope envelope = new Envelope(snapshot.version(), checksum, routes);
            byte[] envelopeBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope);

            Path target = Path.of(properties.getStorePath());
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, ".fib-", ".tmp");
            try {
                Files.write(temp, envelopeBytes);
                tryMoveAtomically(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist FIB snapshot", e);
        }
    }

    private static void tryMoveAtomically(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            // Fallback for file systems without atomic rename: same-dir move is still
            // effectively atomic for readers that only open the final path.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Loads and verifies the last committed snapshot; a missing or bad file yields empty. */
    public Optional<FibSnapshot> load() {
        Path target = Path.of(properties.getStorePath());
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            byte[] raw = Files.readAllBytes(target);
            Envelope envelope = objectMapper.readValue(raw, Envelope.class);
            byte[] payload = objectMapper.writeValueAsBytes(envelope.routes());
            String actual = sha256(payload);
            String expected = envelope.payloadSha256();
            if (expected == null || !MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    actual.getBytes(StandardCharsets.UTF_8))) {
                quarantine(target, "checksum mismatch");
                return Optional.empty();
            }
            List<RouteEntry> routes = new ArrayList<>();
            for (PersistedRoute pr : envelope.routes()) {
                List<NextHop> hops = new ArrayList<>();
                for (PersistedHop ph : pr.nextHops()) {
                    hops.add(new NextHop(ph.id(), IpAddress.parse(ph.address()),
                            ph.weight(), ph.enabled()));
                }
                routes.add(new RouteEntry(IpPrefix.parse(pr.prefix()), pr.source(),
                        pr.adminDistance(), hops));
            }
            FibSnapshot snapshot = FibSnapshot.of(envelope.version(), routes, null, null);
            return Optional.of(snapshot);
        } catch (RuntimeException | IOException corrupt) {
            quarantine(target, String.valueOf(corrupt.getMessage()));
            return Optional.empty();
        }
    }

    private static void quarantine(Path target, String reason) {
        try {
            Path bad = target.resolveSibling(target.getFileName() + ".corrupt."
                    + System.currentTimeMillis());
            Files.move(target, bad, StandardCopyOption.REPLACE_EXISTING);
            log.warn("ignored corrupted FIB state file ({}); moved to {}", reason, bad);
        } catch (IOException moveFailed) {
            log.warn("ignored corrupted FIB state file ({}); could not quarantine: {}",
                    reason, moveFailed.getMessage());
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
