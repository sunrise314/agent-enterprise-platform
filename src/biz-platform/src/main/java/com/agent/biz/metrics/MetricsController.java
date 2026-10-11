package com.agent.biz.metrics;

import com.agent.biz.auth.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标查询接口：聚合指标 + 时序序列。
 *
 * 对应 docs/03_模块设计/03_06_observability.md。
 * demo：内存聚合；生产：InfluxDB/PostgreSQL + Grafana。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsStore store;

    public MetricsController(MetricsStore store) {
        this.store = store;
    }

    /** 汇总指标（默认最近 5 分钟）。 */
    @GetMapping("/summary")
    public Map<String, Object> summary(
            @RequestParam(value = "windowMs", defaultValue = "300000") long windowMs) {
        MetricsStore.Summary s = store.summary(windowMs);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("calls", s.calls());
        data.put("llmCalls", s.llmCalls());
        data.put("tokenIn", s.tokenIn());
        data.put("tokenOut", s.tokenOut());
        data.put("tokenTotal", s.tokenIn() + s.tokenOut());
        data.put("failedCalls", s.failedCalls());
        data.put("p95LatencyMs", s.p95LatencyMs());
        data.put("avgLatencyMs", s.avgLatencyMs());
        return Map.of("code", 0, "data", data);
    }

    /** 时序序列（按分钟，供 Grafana/折线图）。 */
    @GetMapping("/series")
    public Map<String, Object> series() {
        return Map.of("code", 0, "data", store.series());
    }
}
