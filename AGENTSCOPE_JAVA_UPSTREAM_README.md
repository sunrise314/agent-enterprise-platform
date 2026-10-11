# src/agentscope-java（AgentScope-Java 上游源码）

> 本目录存放 **AgentScope-Java 上游源码**（阿里开源，Apache 2.0），已成功拉取到本机并编译验证。

## 拉取状态（2026-10-10）

- ✅ 已通过国内镜像源 `gitclone.com` 成功拉取（直连 GitHub 因国内网络不稳定失败，`early EOF`）
- ✅ 已解决 Windows 路径超限问题（`git config core.longpaths true`）
- ✅ **版本：`2.1.0-SNAPSHOT`**，group/artifact：`io.agentscope:agentscope-core|agentscope-harness`
- ✅ 核心模块已 `mvn install` 到本地 Maven 仓库（`agentscope-dependencies-bom` / `agentscope-core` / `agentscope-harness`，BUILD SUCCESS）
- ✅ `biz-platform` 已通过 `io.agentscope:agentscope-harness:2.1.0-SNAPSHOT` 编译成功

## 来源

- 上游地址：https://github.com/agentscope-ai/agentscope-java
- 文档：https://java.agentscope.io/zh/intro.html

## 二次开发原则（重要）

1. **尽量不修改上游源码**：优先通过自定义 Tool、事件监听、SPI、配置扩展功能；
2. 确需修改的文件，**必须在文件头标注「本文件已被我方修改」**，保留原版权声明（Apache 2.0 合规要求，见 docs/07_开源合规.md）；
3. 底座升级时，直接拉取上游最新版本合并，降低维护成本。

## 已确认的关键 API（供 biz-platform 编码参考）

```java
// Agent 构建
ReActAgent agent = ReActAgent.builder()
        .name("biz-assistant")
        .sysPrompt("...")
        .model("dashscope:qwen-plus")        // ModelRegistry 自动解析，key 读 DASHSCOPE_API_KEY
        .toolkit(toolkit)
        .middleware(new AgentTraceMiddleware())
        .build();

// 工具定义（注解式）
@Tool(name = "kb_search", description = "...", readOnly = true)
public String kbSearch(@ToolParam(name = "query", description = "...") String query) { ... }
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new BizTools());

// 调用
Msg result = agent.call(new UserMessage(query)).block();   // Mono<Msg>
String answer = result.getTextContent();
agent.streamEvents(msg).doOnNext(e -> { if (e instanceof TextBlockDeltaEvent d) ... }).blockLast();
```

## 官方自带示例（agentscope-examples/documentation2/...）——强烈建议先读

| 场景 | 示例 |
|---|---|
| 工具调用 | `tool/ToolCallingExample.java` |
| RAG | `rag/ApplicationLayerRagExample.java` |
| 状态持久化 | `state/StateExample.java`、`StateAutoSaveExample.java` |
| 事件流 | `streaming/AgentEventStreamExample.java` |
| 流式输出 | `streaming/StreamingConsoleExample.java` |
| MCP | `mcp/McpStreamableHttpExample.java` |
| 评估 JEV | `jev/JevTraceEvaluationExample.java` |

## 编译安装命令

```bash
cd src/agentscope-java
mvn -pl agentscope-dependencies-bom,agentscope-core,agentscope-harness -am install -DskipTests -B
```

> 说明：只 install 需要的核心模块，避免编译整个大仓库（examples/extensions 含大量扩展依赖）。
