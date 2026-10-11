package com.agent.biz.eval;

import com.agent.biz.eval.EvalRun.CaseResult;
import com.agent.biz.eval.MetricCollector.Summary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估接口：触发批次 + 拉取报告。
 *
 * 对应 docs/03_模块设计/03_04_evaluation.md
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunService evalRunService;
    private final BadCaseRepository badCaseRepo;

    public EvalController(EvalRunService evalRunService, BadCaseRepository badCaseRepo) {
        this.evalRunService = evalRunService;
        this.badCaseRepo = badCaseRepo;
    }

    /** 触发评估批次。body: {"name":"v1.0-regression","testcaseFilter":{"type":"all"}} */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? "unnamed" : String.valueOf(body.get("name"));
        String type = "all";
        Object filter = body.get("testcaseFilter");
        if (filter instanceof Map<?, ?> fm && fm.get("type") != null) {
            type = String.valueOf(fm.get("type"));
        }
        EvalRun run = evalRunService.run(name, type);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.id());
        data.put("total", run.total());
        return Map.of("code", 0, "data", data);
    }

    /** 拉取评估报告。 */    @GetMapping("/run/{id}")
    public Map<String, Object> report(@PathVariable long id) {
        EvalRun run = evalRunService.get(id);
        if (run == null) {
            return Map.of("code", 1, "msg", "run not found: " + id);
        }
        Summary s = run.summary();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("taskCompletionRate", s.taskCompletionRate());
        metrics.put("toolCallAccuracy", s.toolCallAccuracy());
        metrics.put("hallucinationRate", s.hallucinationRate());
        metrics.put("p95LatencyMs", s.p95LatencyMs());
        metrics.put("avgTokenTotal", s.avgToken());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.id());
        data.put("name", run.name());
        data.put("type", run.type());
        data.put("total", run.total());
        data.put("success", run.success());
        data.put("fail", run.fail());
        data.put("metrics", metrics);
        data.put("cases", run.cases().stream().map(this::caseView).toList());
        return Map.of("code", 0, "data", data);
    }

    /** 拉取坏例库（失败/幻觉/低分自动沉淀，供回归扩充）。 */
    @GetMapping("/bad-cases")
    public Map<String, Object> badCases() {
        return Map.of("code", 0, "data", badCaseRepo.list());
    }

    private Map<String, Object> caseView(CaseResult c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("caseId", c.caseId());
        m.put("type", c.type());
        m.put("question", c.question());
        m.put("answer", c.answer());
        m.put("success", c.success());
        m.put("hallucination", c.hallucination());
        m.put("failReason", c.failReason());
        m.put("latencyMs", c.latencyMs());
        m.put("tokenTotal", c.tokenTotal());
        m.put("semanticScore", c.semanticScore());
        m.put("judgeComment", c.judgeComment());
        return m;
    }
}
