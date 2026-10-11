package com.agent.biz.eval;

import com.agent.biz.agent.AgentFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM-as-judge：用 LLM（DeepSeek）对 Agent 回答做语义打分，补充关键词断言。
 *
 * 对应 docs/03_模块设计/03_04_evaluation.md「LLM-as-judge 设计」。
 * 设计要点：
 *  - 固定 judge 模型与 judge prompt，保证相对可对比
 *  - judge 不生成答案，只判断「输出是否满足成功规则」
 *  - 结果作相对参考，不作为绝对真值
 */
@Component
public class LlmJudge {

    private static final String JUDGE_PROMPT = """
            你是严格的 Agent 回答评估裁判。请根据【问题】和【期望/禁用关键词】判断【回答】是否满足要求。

            【问题】%s
            【期望答案应包含的关键词】%s
            【回答中不得出现的关键词】%s
            【Agent 回答】%s

            请从三个维度评分并只输出 JSON（不要输出其他内容）：
            {
              "score": <1-5 整数，3 以上表示满足要求>,
              "hallucination": <true/false，是否包含无依据的编造>,
              "comment": "<一句话评语>"
            }
            """;

    private final AgentFactory factory;
    private final ObjectMapper om;
    private final AtomicReference<ReActAgent> judgeRef = new AtomicReference<>();

    public LlmJudge(AgentFactory factory, ObjectMapper om) {
        this.factory = factory;
        this.om = om;
    }

    /** 打分结果。 */
    public record JudgeScore(int score, boolean hallucination, String comment) {
    }

    public JudgeScore judge(EvalCase c, String answer) {
        String prompt = JUDGE_PROMPT.formatted(
                c.question() == null ? "" : c.question(),
                String.join(",", c.expectedKeywords() == null ? List.of() : c.expectedKeywords()),
                String.join(",", c.forbiddenKeywords() == null ? List.of() : c.forbiddenKeywords()),
                answer == null ? "" : answer);
        try {
            ReActAgent judge = judgeRef.updateAndGet(existing -> existing != null
                    ? existing
                    : factory.build("judge",
                            "你只做评估裁判，不生成答案，只输出 JSON。",
                            null));
            Msg result = judge.call(List.of(new UserMessage(prompt)),
                            RuntimeContext.builder().userId("judge").sessionId("judge").build())
                    .block();
            return parse(result == null ? "" : result.getTextContent());
        } catch (Exception e) {
            return new JudgeScore(0, false, "judge error: " + e.getMessage());
        }
    }

    private JudgeScore parse(String text) {
        try {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new JudgeScore(0, false, "judge non-json output");
            }
            JsonNode node = om.readTree(text.substring(start, end + 1));
            return new JudgeScore(
                    node.path("score").asInt(0),
                    node.path("hallucination").asBoolean(false),
                    node.path("comment").asText(""));
        } catch (Exception e) {
            return new JudgeScore(0, false, "judge parse error");
        }
    }
}
