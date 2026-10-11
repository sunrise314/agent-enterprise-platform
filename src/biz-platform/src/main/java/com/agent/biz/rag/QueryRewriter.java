package com.agent.biz.rag;

import com.agent.biz.agent.AgentFactory;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Query 改写器：把用户口语问题改写为更适合企业知识库检索的 query。
 *
 * 对应 docs/03_模块设计/03_02_rag_module.md「Query 改写」。
 * 设计：
 *  - LLM（DeepSeek）改写：去口语、提取关键实体/术语、补全知识库相关词
 *  - 失败兜底：返回原 query（不阻断检索）
 *  - 改写独立于检索，可在网关侧/检索前置统一接入
 */
@Component
public class QueryRewriter {

    private static final String REWRITE_PROMPT = """
            你是企业知识库检索的 query 改写器。
            把用户的口语问题改写为适合关键词检索的简洁 query。

            规则：
            - 只输出改写后的 query，一句话，不要解释
            - 保留关键实体与术语（制度名、流程名、数值、名词）
            - 去掉口语、代词、语气词、冗余修饰
            - 必要时补全知识库相关词（如「报销」补全为「报销制度」「报销流程」）

            示例：
            用户：公司报销是咋规定的？多久内要提交？
            改写：公司报销制度 报销时限 提交时限
            用户：我们外包人员能碰生产库吗
            改写：外包人员 生产数据库 访问权限

            用户问题：%s
            """;

    private final AgentFactory factory;
    private final AtomicReference<ReActAgent> rewriterRef = new AtomicReference<>();

    public QueryRewriter(AgentFactory factory) {
        this.factory = factory;
    }

    /** 改写 query；失败/异常兜底返回原 query。 */
    public String rewrite(String query) {
        if (query == null || query.trim().isEmpty()) {
            return query;
        }
        try {
            ReActAgent rewriter = rewriterRef.updateAndGet(existing -> existing != null
                    ? existing
                    : factory.build("query-rewriter",
                            "你只做检索 query 改写，不回答知识问题，只输出一句话。", null));
            Msg result = rewriter.call(List.of(new UserMessage(REWRITE_PROMPT.formatted(query))),
                            RuntimeContext.builder().userId("rewriter").sessionId("rewriter").build())
                    .block();
            String rewritten = result == null ? "" : result.getTextContent().trim();
            return rewritten.isBlank() || rewritten.length() > 80 ? query : rewritten;
        } catch (Exception e) {
            return query;
        }
    }
}
