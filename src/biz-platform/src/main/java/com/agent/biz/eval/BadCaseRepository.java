package com.agent.biz.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 坏例沉淀仓库：失败 / 幻觉 / 低分 case 自动收集，供下次回归集扩充。
 *
 * 设计：
 *  - 内存 List 保存本次会话坏例
 *  - 落盘 testcases/bad-cases.json，跨重启持久，可人工审核后合入正式回归集
 *  - 对应 docs/03_模块设计/03_04_evaluation.md「bad case 自动沉淀」
 */
@Component
public class BadCaseRepository {

    private static final Logger log = LoggerFactory.getLogger(BadCaseRepository.class);
    private static final String FILE = "D:/ProgramProject/agent-enterprise-platform/testcases/bad-cases.json";

    private final ObjectMapper om;
    private final List<BadCase> badCases = new CopyOnWriteArrayList<>();

    public BadCaseRepository(ObjectMapper om) {
        this.om = om;
        load();
    }

    public record BadCase(String caseId, String type, String question,
                          boolean fail, boolean hallucination, Integer semanticScore,
                          String reason, long capturedAtMs) {
    }

    /** 收集坏例并落盘。 */
    public void capture(EvalCase c, boolean fail, boolean hallucination,
                        Integer semanticScore, String reason) {
        BadCase bad = new BadCase(c.id(), c.type(), c.question(),
                fail, hallucination, semanticScore, reason, System.currentTimeMillis());
        badCases.add(bad);
        persist();
    }

    public List<BadCase> list() {
        return List.copyOf(badCases);
    }

    /** 判断是否为需沉淀的坏例：失败 / 判幻觉 / 语义低分(<=3)。 */
    public boolean isBad(EvalRun.CaseResult r) {
        return !r.success() || r.hallucination()
                || (r.semanticScore() != null && r.semanticScore() <= 3);
    }

    private void persist() {
        try {
            Path p = Paths.get(FILE);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, om.writerWithDefaultPrettyPrinter().writeValueAsString(badCases));
        } catch (IOException e) {
            log.warn("bad-cases persist failed: {}", e.getMessage());
        }
    }

    private void load() {
        try {
            Path p = Paths.get(FILE);
            if (Files.exists(p)) {
                String json = Files.readString(p);
                if (!json.isBlank()) {
                    BadCase[] arr = om.readValue(json, BadCase[].class);
                    for (BadCase b : arr) {
                        badCases.add(b);
                    }
                    log.info("loaded {} bad cases from {}", badCases.size(), FILE);
                }
            }
        } catch (IOException e) {
            log.warn("bad-cases load failed: {}", e.getMessage());
        }
    }

    static long now() {
        return Instant.now().toEpochMilli();
    }
}
