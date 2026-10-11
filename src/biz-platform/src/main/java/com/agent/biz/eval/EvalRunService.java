package com.agent.biz.eval;

import com.agent.biz.agent.AgentServiceImpl;
import com.agent.biz.eval.EvalRun.CaseResult;
import com.agent.biz.eval.MetricCollector.CaseMetrics;
import com.agent.biz.eval.MetricCollector.Summary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 评估批次执行服务。
 *
 * 流程：加载评测集 → 按 type 过滤 → 逐条调用 Agent → EvalJudge 判定 → MetricCollector 聚合。
 * 结果按 runId 暂存（内存），供报告接口拉取。
 */
@Service
public class EvalRunService {

    private final AgentServiceImpl agentService;
    private final EvalJudge judge;
    private final MetricCollector collector;
    private final List<EvalCase> cases;
    private final LlmJudge llmJudge;
    private final BadCaseRepository badCaseRepo;

    private final ConcurrentMap<Long, EvalRun> runs = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    public EvalRunService(AgentServiceImpl agentService, EvalJudge judge,
                          MetricCollector collector, EvalCaseLoader loader, LlmJudge llmJudge,
                          BadCaseRepository badCaseRepo) {
        this.agentService = agentService;
        this.judge = judge;
        this.collector = collector;
        this.cases = loader.load();
        this.llmJudge = llmJudge;
        this.badCaseRepo = badCaseRepo;
    }

    /** 触发一次评估批次。 */
    public EvalRun run(String name, String type) {
        long id = idGen.incrementAndGet();
        String filter = type == null || type.isBlank() ? "all" : type.toLowerCase();

        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : cases) {
            if (!"all".equals(filter) && !c.type().equalsIgnoreCase(filter)) {
                continue;
            }
            long start = System.currentTimeMillis();
            AgentServiceImpl.ChatResult r =
                    agentService.chat(c.question(), "eval-" + c.id(), "eval-run-" + id);
            long latency = System.currentTimeMillis() - start;

            EvalJudge.JudgeResult j = judge.judge(c, r.answer());
            int tokenTotal = r.tokenIn() + r.tokenOut();

            // LLM-as-judge：在关键词断言之上补充语义打分（相对参考，非真值）
            LlmJudge.JudgeScore semantic = llmJudge.judge(c, r.answer());
            CaseResult cr = new CaseResult(c.id(), c.type(), c.question(), r.answer(),
                    j.success(), j.hallucination(), j.failReason(), latency, tokenTotal,
                    semantic.score(), semantic.comment());
            results.add(cr);

            // 坏例自动沉淀：失败 / 幻觉 / 语义低分(<=3) → 入 bad-cases 库，供下次回归
            if (badCaseRepo.isBad(cr)) {
                badCaseRepo.capture(c, cr.success(), cr.hallucination(),
                        cr.semanticScore(), cr.failReason());
            }
        }

        // 聚合指标（toolCallOk 首版以 success 近似，真实工具调用采集后续接入）
        List<CaseMetrics> metrics = results.stream()
                .map(r -> new CaseMetrics(r.success(), r.success(), r.hallucination(),
                        r.tokenTotal(), 0, r.latencyMs(), 0, r.failReason()))
                .toList();
        Summary summary = collector.summarize(metrics);

        int success = (int) results.stream().filter(r -> r.success()).count();
        int fail = results.size() - success;
        EvalRun run = new EvalRun(id, name, filter, results.size(), success, fail, summary, results);
        runs.put(id, run);
        return run;
    }

    public EvalRun get(long id) {
        return runs.get(id);
    }
}
