package com.agent.biz.agent;

import com.agent.biz.audit.AuditLogger;
import com.agent.biz.auth.TenantInfo;
import com.agent.biz.rag.RagService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 业务工具集：通过 AgentScope-Java 的 @Tool 注解注册为 Agent 可调用工具。
 *
 * 对应 docs/03_模块设计/03_03_biz_tools.md
 * 依据官方 ToolCallingExample 的真实用法编写。
 *
 * 注意：工具 description 必须写清楚「什么时候用、参数含义」，
 * 否则 LLM 容易选错工具（工具调用准确率下降）。
 */
@Component
public class BizTools {

    private static final Logger log = LoggerFactory.getLogger(BizTools.class);

    private final RagService ragService;
    private final AuditLogger audit;

    public BizTools(RagService ragService, AuditLogger audit) {
        this.ragService = ragService;
        this.audit = audit;
    }

    /**
     * 知识库检索工具：Agent 自主决定何时检索企业文档。
     */
    @Tool(
            name = "kb_search",
            description = "检索企业内部知识库，返回与问题相关的文档片段。"
                    + "当用户询问制度、流程、合同、技术文档等企业内部信息时必须调用本工具。",
            readOnly = true)
    public String kbSearch(
            @ToolParam(name = "query", description = "要检索的问题或关键词") String query,
            @ToolParam(name = "topK", description = "返回片段数量，默认 5") int topK,
            TenantInfo tenant) {
        log.info("kb_search called: query=[{}] topK=[{}] tenant=[{}]",
                query, topK, tenant == null ? null : tenant.tenantId());
        long start = System.currentTimeMillis();
        int k = topK <= 0 ? 5 : topK;
        String t = tenant == null ? "tenant-0" : tenant.tenantId();
        String result = ragService.searchSummary(query, k, t);
        long cost = System.currentTimeMillis() - start;
        audit.record(t, tenant == null ? null : tenant.userId(), "tool.kb_search",
                "knowledge-base", "q=" + query, !result.startsWith("(未检索"), cost);
        log.info("kb_search result_len={} preview=[{}]", result == null ? -1 : result.length(),
                result == null ? "" : result.substring(0, Math.min(80, result.length())));
        return result;
    }

    /**
     * 获取当前时间（示例工具）。
     */
    @Tool(name = "get_current_time", description = "获取指定时区的当前时间", readOnly = true)
    public String getCurrentTime(
            @ToolParam(name = "timezone", description = "IANA 时区名，如 Asia/Shanghai") String timezone) {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of(timezone));
            return String.format("Current time in %s: %s",
                    timezone, now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception e) {
            return "Error: invalid timezone, try 'Asia/Shanghai'";
        }
    }

    /**
     * 构建业务 Toolkit 并注册本类全部 @Tool 方法。
     */
    public Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(this);
        return toolkit;
    }
}
