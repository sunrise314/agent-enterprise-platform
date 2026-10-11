package com.agent.biz.metrics;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存指标存储（demo）：按分钟聚合调用/token/耗时，环形缓冲保留最近 N 分钟。
 *
 * 无外部依赖，可真实采集并经 /api/metrics 查询；生产替换为 InfluxDB/PostgreSQL（见接口注释）。
 */
@Profile({"demo", "milvus"})
@Component
public class InMemoryMetricsStore implements MetricsStore {

    private static final int MAX_MINUTES = 240; // 保留最近 4 小时

    private final ConcurrentHashMap<Long, MinuteBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void recordLlmCall(String tenantId, int tokenIn, int tokenOut, long costMs) {
        bucket().llmCalls++;
        bucket().tokenIn += tokenIn;
        bucket().tokenOut += tokenOut;
        bucket().llmLatencies.add(costMs);
    }

    @Override
    public void recordAgentCall(String tenantId, String sessionKey, boolean success, long costMs) {
        MinuteBucket b = bucket();
        b.calls++;
        b.latencies.add(costMs);
        if (!success) {
            b.failedCalls++;
        }
    }

    @Override
    public void recordToolCall(String tenantId, String toolName, boolean success, long costMs) {
        MinuteBucket b = bucket();
        b.toolCalls++;
        b.toolLatencies.add(costMs);
        if (!success) {
            b.failedToolCalls++;
        }
    }

    @Override
    public Summary summary(long windowMs) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;
        List<MinuteBucket> relevant = buckets.entrySet().stream()
                .filter(e -> e.getKey() >= minuteKey(windowStart))
                .map(Map.Entry::getValue)
                .toList();

        long calls = relevant.stream().mapToLong(b -> b.calls).sum();
        long llmCalls = relevant.stream().mapToLong(b -> b.llmCalls).sum();
        long tokenIn = relevant.stream().mapToLong(b -> b.tokenIn).sum();
        long tokenOut = relevant.stream().mapToLong(b -> b.tokenOut).sum();
        long failedCalls = relevant.stream().mapToLong(b -> b.failedCalls).sum();

        List<Long> allLat = relevant.stream().flatMap(b -> b.latencies.stream()).toList();
        double p95 = p95(allLat);
        double avg = allLat.isEmpty() ? 0 : allLat.stream().mapToLong(Long::longValue).average().orElse(0);
        return new Summary(calls, llmCalls, tokenIn, tokenOut, failedCalls, p95, avg);
    }

    @Override
    public List<DataPoint> series() {
        return buckets.entrySet().stream()
                .sorted(Comparator.comparingLong(Map.Entry::getKey))
                .map(e -> {
                    MinuteBucket b = e.getValue();
                    double avg = b.latencies.isEmpty()
                            ? 0 : b.latencies.stream().mapToLong(Long::longValue).average().orElse(0);
                    return new DataPoint(e.getKey(), b.calls, b.tokenIn + b.tokenOut, avg);
                })
                .toList();
    }

    private MinuteBucket bucket() {
        long key = minuteKey(System.currentTimeMillis());
        buckets.computeIfAbsent(key, k -> {
            if (buckets.size() > MAX_MINUTES) {
                long oldest = buckets.keySet().stream().min(Long::compareTo).orElse(key);
                buckets.remove(oldest);
            }
            return new MinuteBucket();
        });
        return buckets.get(key);
    }

    private long minuteKey(long ts) {
        return ts / 60_000L;
    }

    private double p95(List<Long> vals) {
        if (vals.isEmpty()) {
            return 0;
        }
        List<Long> sorted = vals.stream().sorted().collect(Collectors.toList());
        int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, idx));
    }

    /** 单分钟桶。 */
    private static class MinuteBucket {
        long calls;
        long llmCalls;
        long tokenIn;
        long tokenOut;
        long failedCalls;
        long toolCalls;
        long failedToolCalls;
        final List<Long> latencies = new ArrayList<>();
        final List<Long> llmLatencies = new ArrayList<>();
        final List<Long> toolLatencies = new ArrayList<>();
    }
}
