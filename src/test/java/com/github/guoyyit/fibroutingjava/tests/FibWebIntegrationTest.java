package com.github.guoyyit.fibroutingjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：通过 REST 验证 增删改-提交-查找-健康切换-聚合-非法拒绝 全链路。
 * 使用临时持久化目录，避免污染开发数据。
 */
@SpringBootTest(properties = {
        "fib.persistence-file=target/test-data/fib-web-it.json",
        "fib.max-routes=100",
        "fib.default-route-enabled=false"
})
@AutoConfigureMockMvc
class FibWebIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @org.junit.jupiter.api.BeforeAll
    static void cleanPersistedData() throws Exception {
        // 隔离持久化：删除上次运行残留，保证从空表开始
        java.nio.file.Path dir = java.nio.file.Paths.get("target/test-data");
        if (java.nio.file.Files.isDirectory(dir)) {
            try (var stream = java.nio.file.Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                java.nio.file.Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            }
        }
    }

    private String routeJson(String prefix, String... hopIds) {
        StringBuilder hops = new StringBuilder("[");
        for (int i = 0; i < hopIds.length; i++) {
            if (i > 0) {
                hops.append(',');
            }
            hops.append("{\"id\":\"").append(hopIds[i])
                    .append("\",\"address\":\"192.0.2.").append(i + 1)
                    .append("\",\"weight\":1,\"enabled\":true}");
        }
        hops.append(']');
        return "{\"prefix\":\"" + prefix + "\",\"adminDistance\":0,\"nextHops\":" + hops + "}";
    }

    @Test
    void fullWorkflowOverHttp() throws Exception {
        // 空表查找 => UNREACHABLE
        mvc.perform(get("/fib/lookup").param("destination", "10.5.5.5").param("flowKey", "x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("UNREACHABLE"));

        // 批量插入两条
        String body = "{\"upsert\":[" + routeJson("10.0.0.0/8", "a")
                + "," + routeJson("10.1.0.0/16", "b") + "],\"autocommit\":true}";
        mvc.perform(post("/fib/routes/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.routeCount").value(2));

        // LPM：更具体前缀胜出
        mvc.perform(get("/fib/lookup").param("destination", "10.1.9.9").param("flowKey", "f1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MATCHED"))
                .andExpect(jsonPath("$.matchedPrefix").value("10.1.0.0/16"))
                .andExpect(jsonPath("$.nextHopId").value("b"));

        // 暂存但不提交 => 查询不可见（未提交不生效）
        String staged = "{\"upsert\":[" + routeJson("11.0.0.0/8", "s") + "],\"autocommit\":false}";
        mvc.perform(post("/fib/routes/batch").contentType(MediaType.APPLICATION_JSON).content(staged))
                .andExpect(status().isOk()).andExpect(jsonPath("$.committed").value(false));
        mvc.perform(get("/fib/lookup").param("destination", "11.1.1.1"))
                .andExpect(jsonPath("$.outcome").value("UNREACHABLE"));
        // discard 后再提交为空操作
        mvc.perform(post("/fib/discard")).andExpect(status().isOk());
        mvc.perform(post("/fib/commit")).andExpect(status().isOk())
                .andExpect(jsonPath("$.routeCount").value(2));

        // 健康切换：b 失效后，10.1.9.9 回退到更短前缀的 a
        mvc.perform(post("/fib/health").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextHopId\":\"b\",\"healthy\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/fib/lookup").param("destination", "10.1.9.9").param("flowKey", "f1"))
                .andExpect(jsonPath("$.nextHopId").value("a"));

        // 非法变更：权重 0 => 409 + 可区分原因码
        String bad = "{\"prefix\":\"12.0.0.0/8\",\"adminDistance\":0,"
                + "\"nextHops\":[{\"id\":\"z\",\"address\":\"192.0.2.9\",\"weight\":0}]}";
        mvc.perform(post("/fib/routes/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upsert\":[" + bad + "],\"autocommit\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("INVALID_WEIGHT"));

        // 聚合成功：两个相邻 /24 -> /23
        String agg = "{\"members\":[\"10.2.0.0/24\",\"10.2.1.0/24\"],"
                + "\"adminDistance\":0,"
                + "\"nextHops\":[{\"id\":\"agg\",\"address\":\"198.51.100.1\",\"weight\":1}]}";
        mvc.perform(post("/fib/aggregate").contentType(MediaType.APPLICATION_JSON).content(agg))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregatePrefix").value("10.2.0.0/23"));

        // IPv6
        String v6 = "{\"prefix\":\"2001:db8::/32\",\"adminDistance\":0,"
                + "\"nextHops\":[{\"id\":\"v6\",\"address\":\"2001:db8::1\",\"weight\":1}]}";
        mvc.perform(post("/fib/routes/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upsert\":[" + v6 + "],\"autocommit\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/fib/lookup").param("destination", "2001:db8:1::1"))
                .andExpect(jsonPath("$.nextHopId").value("v6"));
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        mvc.perform(get("/fib/lookup").param("destination", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("INVALID_REQUEST"));
    }

    @Test
    void timeoutQueryReturns408() throws Exception {
        mvc.perform(get("/fib/lookup")
                        .param("destination", "10.1.1.1")
                        .param("timeoutNanos", "1"))
                .andExpect(status().isRequestTimeout())
                .andExpect(jsonPath("$.reason").value("LOOKUP_TIMEOUT"));
    }
}
