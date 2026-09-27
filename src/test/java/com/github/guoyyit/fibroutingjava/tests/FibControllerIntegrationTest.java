package com.github.guoyyit.fibroutingjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "fib.persistence-enabled=false",
        "fib.max-routes=100000"
})
@AutoConfigureMockMvc
class FibControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String routeJson(String prefix, String source, Integer ad, List<Map<String, Object>> hops)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "prefix", prefix,
                "source", source,
                "adminDistance", ad,
                "nextHops", hops));
    }

    private Map<String, Object> hop(String id, String address, int weight) {
        return Map.of("id", id, "address", address, "weight", weight, "enabled", true);
    }

    @Test
    void fullLifecycleAcrossHttpApi() throws Exception {
        // commit a /8 with two weighted hops
        mockMvc.perform(post("/api/fib/transactions")).andExpect(status().isOk());
        String body = routeJson("10.0.0.0/8", "static", 10,
                List.of(hop("a", "192.0.2.1", 1), hop("b", "192.0.2.2", 2)));
        mockMvc.perform(post("/api/fib/routes").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STAGED"));
        mockMvc.perform(post("/api/fib/commit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.version").value(1));

        // lookup forwards and reports the matched prefix deterministically
        mockMvc.perform(get("/api/fib/lookup").param("destination", "10.4.5.6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("FORWARDED"))
                .andExpect(jsonPath("$.matchedPrefix").value("10.0.0.0/8"))
                .andExpect(jsonPath("$.version").value(1));

        // a miss is UNREACHABLE (no default configured here)
        mockMvc.perform(get("/api/fib/lookup").param("destination", "8.8.8.8"))
                .andExpect(jsonPath("$.outcome").value("UNREACHABLE"));

        // zero timeout yields TIMEOUT, never a partial FORWARDED
        mockMvc.perform(get("/api/fib/lookup")
                        .param("destination", "10.4.5.6").param("timeoutNanos", "0"))
                .andExpect(jsonPath("$.outcome").value("TIMEOUT"));

        // malformed destination is a 400 with a distinguishable code
        mockMvc.perform(get("/api/fib/lookup").param("destination", "not-an-ip"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_PREFIX"));

        // learn which hop 10.4.5.6 uses, then disable it: selection must switch
        String firstId = mockMvc.perform(get("/api/fib/lookup").param("destination", "10.4.5.6"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"nextHopId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/fib/health")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("hopId", firstId, "enabled", false))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/fib/lookup").param("destination", "10.4.5.6"))
                .andExpect(jsonPath("$.outcome").value("FORWARDED"))
                .andExpect(jsonPath("$.nextHopId").value(firstId.equals("a") ? "b" : "a"));

        // illegal change (self loop) rejected at commit with code LOOP_DETECTED
        mockMvc.perform(post("/api/fib/transactions")).andExpect(status().isOk());
        String loopBody = routeJson("172.16.0.0/12", "static", 10,
                List.of(hop("loop", "172.16.0.5", 1)));
        mockMvc.perform(post("/api/fib/routes").contentType(MediaType.APPLICATION_JSON).content(loopBody))
                .andExpect(status().isOk()); // staging is non-judgemental
        mockMvc.perform(post("/api/fib/commit"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("LOOP_DETECTED"));

        // aggregation summarises two /24s into a /23 and commits atomically
        String aggregate = objectMapper.writeValueAsString(Map.of(
                "components", List.of("10.10.0.0/24", "10.10.1.0/24"),
                "source", "aggregator",
                "adminDistance", 100,
                "nextHops", List.of(hop("agg", "198.51.100.1", 1))));
        mockMvc.perform(post("/api/fib/aggregate").contentType(MediaType.APPLICATION_JSON).content(aggregate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aggregatePrefix").value("10.10.0.0/23"))
                .andExpect(jsonPath("$.componentCount").value(2));
        // the aggregate forwards traffic in the gap not covered by the /8 ECMP specifics
        mockMvc.perform(get("/api/fib/lookup").param("destination", "10.10.1.9"))
                .andExpect(jsonPath("$.matchedPrefix").value("10.10.0.0/23"));

        // delete the /8 and commit; its destinations become UNREACHABLE
        mockMvc.perform(post("/api/fib/transactions")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/fib/routes").param("prefix", "10.0.0.0/8"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/fib/commit")).andExpect(status().isOk());
        mockMvc.perform(get("/api/fib/lookup").param("destination", "10.4.5.6"))
                .andExpect(jsonPath("$.outcome").value("UNREACHABLE"));
    }
}
