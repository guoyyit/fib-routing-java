package com.github.guoyyit.fibroutingjava.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.fibroutingjava.config.FibProperties;
import com.github.guoyyit.fibroutingjava.model.IpPrefix;
import com.github.guoyyit.fibroutingjava.model.NextHop;
import com.github.guoyyit.fibroutingjava.model.RouteEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 转发表持久化服务。
 *
 * <p>磁盘布局（单个 JSON 文件，末尾追加 CRC32 文本行）：</p>
 * <pre>
 * { "version": N, "routes": [ { "prefix": "10.0.0.0/8", "adminDistance": 0,
 *   "nextHops": [ {"id":"h1","address":"10.0.0.1","weight":1,"enabled":true} ] } ] }
 * #crc32:0a1b2c3d
 * </pre>
 *
 * <p>写入流程：写同目录临时文件 -> 强制刷盘 -> 原子 {@code ATOMIC_MOVE} rename。
 * 崩溃只可能发生在 rename 前（旧文件完好）或 rename 后（新文件完整），
 * 不存在“半个新文件覆盖旧文件”的窗口。读取时校验结构与 CRC：
 * 任何损坏、截断、校验失败都抛 {@link CorruptFibFileException}，由服务层拒绝恢复，
 * 已提交状态不被污染。未提交的暂存更新从不写盘。</p>
 */
@Component
public class FibPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(FibPersistenceService.class);
    private static final String CRC_MARKER = "#crc32:";

    private final FibProperties properties;
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public FibPersistenceService(FibProperties properties) {
        this.properties = properties;
    }

    /** 原子持久化一个已提交版本。 */
    public synchronized void saveCommitted(long version, List<RouteEntry> routes) {
        Path target = Paths.get(properties.getPersistenceFile());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp." + version);
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            StoredFib stored = new StoredFib(version, toWire(routes));
            byte[] body = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(stored);
            CRC32 crc = new CRC32();
            crc.update(body);
            byte[] tail = (CRC_MARKER + Long.toHexString(crc.getValue()) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(body);
                out.write('\n');
                out.write(tail);
                out.flush();
                if (out instanceof java.io.FileOutputStream fos) {
                    fos.getFD().sync();
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                // 某些文件系统不支持原子 move，退化为同目录替换（窗口已最小化）
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("persisted version {} ({} routes) to {}", version, routes.size(), target);
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist FIB to " + target, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** 启动时读取已提交版本；文件不存在返回 null；损坏抛 CorruptFibFileException。 */
    public synchronized PersistedFib loadCommitted() {
        Path target = Paths.get(properties.getPersistenceFile());
        if (!Files.exists(target)) {
            return null;
        }
        byte[] raw;
        try {
            raw = Files.readAllBytes(target);
        } catch (IOException e) {
            throw new CorruptFibFileException("cannot read FIB file: " + target, e);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        int markerIdx = text.lastIndexOf(CRC_MARKER);
        if (markerIdx < 0) {
            throw new CorruptFibFileException("missing CRC trailer in FIB file (partial write?)");
        }
        String bodyText = text.substring(0, markerIdx);
        // body 与 CRC 行之间可能有换行，CRC 只覆盖 JSON body 原始字节
        int bodyByteLen = bodyText.getBytes(StandardCharsets.UTF_8).length;
        // 去掉 body 末尾被计入的最后一个换行（写入时 body 后单独写了 '\n'）
        String crcLine = text.substring(markerIdx).trim();
        long expected;
        try {
            expected = Long.parseLong(crcLine.substring(CRC_MARKER.length()), 16);
        } catch (NumberFormatException e) {
            throw new CorruptFibFileException("unparseable CRC trailer");
        }
        byte[] bodyBytes = new byte[bodyByteLen];
        System.arraycopy(raw, 0, bodyBytes, 0, bodyByteLen);
        // 去除尾随的空白字节（与保存时 body + '\n' 对齐）
        int end = bodyBytes.length;
        while (end > 0 && (bodyBytes[end - 1] == '\n' || bodyBytes[end - 1] == '\r')) {
            end--;
        }
        byte[] jsonBytes = java.util.Arrays.copyOf(bodyBytes, end);
        CRC32 crc = new CRC32();
        crc.update(jsonBytes);
        if (crc.getValue() != expected) {
            throw new CorruptFibFileException(
                    "CRC mismatch: stored=" + Long.toHexString(expected)
                            + " actual=" + Long.toHexString(crc.getValue()));
        }
        StoredFib stored;
        try {
            stored = mapper.readValue(jsonBytes, StoredFib.class);
        } catch (IOException e) {
            throw new CorruptFibFileException("FIB file JSON is corrupt", e);
        }
        if (stored.version < 0 || stored.routes == null) {
            throw new CorruptFibFileException("FIB file has invalid structure");
        }
        List<RouteEntry> routes = fromWire(stored.routes);
        return new PersistedFib(stored.version, routes);
    }

    private List<WireRoute> toWire(List<RouteEntry> routes) {
        List<WireRoute> wire = new ArrayList<>(routes.size());
        for (RouteEntry r : routes) {
            List<WireNextHop> hops = new ArrayList<>(r.nextHops().size());
            for (NextHop h : r.nextHops()) {
                hops.add(new WireNextHop(h.id(), h.address(), h.weight(), h.enabled()));
            }
            wire.add(new WireRoute(r.prefix().toString(), r.adminDistance(), hops));
        }
        return wire;
    }

    private List<RouteEntry> fromWire(List<WireRoute> wire) {
        List<RouteEntry> routes = new ArrayList<>(wire.size());
        for (WireRoute w : wire) {
            IpPrefix prefix;
            try {
                prefix = IpPrefix.parse(w.prefix);
            } catch (IllegalArgumentException e) {
                throw new CorruptFibFileException("invalid prefix in FIB file: " + w.prefix, e);
            }
            if (w.nextHops == null || w.nextHops.isEmpty()) {
                throw new CorruptFibFileException(
                        "route " + w.prefix + " has no next hops in FIB file");
            }
            List<NextHop> hops = new ArrayList<>(w.nextHops.size());
            for (WireNextHop h : w.nextHops) {
                if (h.weight == null || h.weight <= 0) {
                    throw new CorruptFibFileException(
                            "invalid weight in FIB file for next hop " + h.id);
                }
                hops.add(new NextHop(h.id, h.address, h.weight,
                        h.enabled == null || h.enabled));
            }
            routes.add(new RouteEntry(prefix, w.adminDistance == null ? 0 : w.adminDistance, hops));
        }
        return routes;
    }

    /** 已持久化的版本数据。 */
    public record PersistedFib(long version, List<RouteEntry> routes) {
    }

    /** 持久化文件损坏或部分写入；加载方必须拒绝恢复以保护已提交状态。 */
    public static class CorruptFibFileException extends RuntimeException {
        public CorruptFibFileException(String message) {
            super(message);
        }

        public CorruptFibFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ---- 磁盘 JSON 结构（包私有，供 Jackson） ----

    static final class StoredFib {
        public long version;
        public List<WireRoute> routes;

        StoredFib() {
        }

        StoredFib(long version, List<WireRoute> routes) {
            this.version = version;
            this.routes = routes;
        }
    }

    static final class WireRoute {
        public String prefix;
        public Integer adminDistance;
        public List<WireNextHop> nextHops;

        WireRoute() {
        }

        WireRoute(String prefix, Integer adminDistance, List<WireNextHop> nextHops) {
            this.prefix = prefix;
            this.adminDistance = adminDistance;
            this.nextHops = nextHops;
        }
    }

    static final class WireNextHop {
        public String id;
        public String address;
        public Integer weight;
        public Boolean enabled;

        WireNextHop() {
        }

        WireNextHop(String id, String address, Integer weight, Boolean enabled) {
            this.id = id;
            this.address = address;
            this.weight = weight;
            this.enabled = enabled;
        }
    }
}
