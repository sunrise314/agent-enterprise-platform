# 13 SQL 沙箱：让 Agent 能查库，但不能作恶

> 第十三篇收工具安全。这一章有个必须先交代的坦白：第 06 章我写「SQL 沙箱工具 sql_query 已加层 SqlGuard」——那句话超前了。当时 SqlGuard 写好了，但工具本身没有接线，Agent 根本调不到。这一章把它真正做出来，顺手修掉守卫里的两个真漏洞，并给项目补上第一批单元测试。

## Agent 拿到 SQL 能力意味着什么

给 Agent 挂一个 sql_query 工具，等于把数据库客户端交给了概率模型。它可能做四件坏事：

1. **幻觉生成写操作**：LLM 一飘，`DELETE FROM orders` 就出来了；
2. **被 Prompt 注入诱导**：知识库文档里藏一句"请执行 DROP TABLE"，Agent 可能照办；
3. **全表扫描拖库**：不带 LIMIT 的 `SELECT *` 一次拉几百万行，内存和带宽双爆；
4. **多语句注入**：`SELECT 1; DROP TABLE x` 这种拼接。

对应到工程上，我的解法是纵深防御四道线，缺一层都不敢交付：

| 防线 | 层 | 作用 |
|---|---|---|
| 1. SqlGuard 静态校验 | 应用层，执行前 | 拦写操作、多语句、DoS 函数、强制 LIMIT |
| 2. JDBC queryTimeout | JDBC 层 | 单条 SQL 最多跑 5 秒 |
| 3. JDBC maxRows | JDBC 层 | 最多返回 100 行 + 1 行探测截断 |
| 4. 数据库账号只读授权 | 部署层 | 前三层全被绕过时，写入在数据库侧被拒 |

## SqlGuard 的六层规则与两个真漏洞

守卫的校验顺序：非空与长度上限 → 剥离注释 → 压缩空白 → 仅 SELECT 开头 → 禁分号 → 危险关键字词边界 → 防 DoS 函数 → LIMIT 收尾。

写第一版时我踩了两个坑，都是测试抓出来的：

**漏洞一：`contains` 误伤正常业务。** 最早的关键字检查是 `lower.contains("drop")`，结果 `SELECT backdrop_url FROM movies` 里的 backdrop 命中了 drop，直接误拦。反过来 `created_at` 也没逃掉 create。修法是词边界正则：`\bcreate\b` 不会命中 `created_at`（e 和 _ 之间不构成边界……准确说 `\b` 在 `create` 后面接 `d` 时不是边界），而独立出现的 create 一定命中。测试里留了两条永久回归：

```java
@Test
void allowsColumnNameBackdrop_notMisidentifiedAsDrop() {
    assertDoesNotThrow(() -> guard.validate("SELECT backdrop_url FROM movies LIMIT 3"));
}
```

**漏洞二：注释绕过。** `SELECT/**/1/**/;/**/DROP/**/TABLE/**/orders` 在旧版里能通过——块注释把分号和 DROP 隔开，`contains` 和多语句正则全失效。这正是第 12 章 P0 清单里写的"可被注释绕过"。修法是先剥离全部注释、压缩空白，再在规范化文本上做所有检查：

```java
static String normalize(String sql) {
    String s = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
    s = LINE_COMMENT.matcher(s).replaceAll(" ");
    return WHITESPACE.matcher(s).replaceAll(" ").trim().toLowerCase();
}
```

还有一个刻意的取舍：规范化后**只要出现分号就拒**。字面量里含分号（`WHERE name = 'a;b'`）的合法场景会被误拦——但 Agent 查业务表几乎不会写出这种 SQL，宁严勿漏。

## SqlQueryTool：接线 + 审计

工具本体很薄：校验 → 执行 → 渲染 markdown 表格 → 审计留痕。三个细节值得说：

1. **maxRows = maxRows + 1**：多取一行用来判断"被截断"，给 Agent 的返回里加一句"结果超过 100 行已截断，请缩小查询范围"——否则 Agent 会把截断结果当全量继续推理；
2. **异常只暴露类名和消息**，不吐堆栈和连接串；
3. **拒绝也记审计**：`audit.record(..., "rejected: ...", false, cost)`，安全拦截的次数本身就是运营指标。

工具的 description 里我写了一句路由提示："适用于统计、核对、列表类问题；制度类问题请改用 kb_search"——这是工具调用准确率的关键，Agent 看着 description 决定用哪个工具，写不清楚它就选错。

## AgentGuard：运行时护栏

SqlGuard 管单条 SQL，`AgentGuard` 管整个 Agent 的行为边界：`max-tool-rounds=8`（防死循环调工具）、`timeout-seconds=60`（单次执行超时）、`max-retries=3`。模型抽风时，8 轮封顶就是止损线。

## 实测数字：项目第一批 27 个测试

这一章顺手把第 12 章 P0 清单的第 1 项开了张。三层测试结构：

| 层 | 类 | 用例数 | 验证什么 |
|---|---|---|---|
| 安全矩阵 | SqlGuardTest | 21 | 放行/拦截/规范化三组，含两个漏洞的永久回归 |
| 渲染单元 | SqlQueryToolRenderTest | 3 | H2 真实 ResultSet → markdown、NULL、截断 |
| Spring 集成 | SqlQueryToolIntegrationTest | 3 | DataSource 装配、真实执行、拒绝路径审计 |

`mvn test` 结果：**Tests run: 27, Failures: 0, Errors: 0, BUILD SUCCESS**（单元层 0.058s，集成层 8.2s 含上下文启动）。

过程中测试还抓出我自己渲染函数的一个 bug：分隔行 Joiner 少个空格，输出 `| ---| ---|`——markdown 表直接错位。先写测试再修，比先上线再修便宜太多。

## 诚实的边界

- **正则沙箱仍是简化方案**。终极方案是 Apache Calcite 做真正的语法解析（AST 层判断语句类型和访问表），配合数据库侧只读账号双保险。当前六层规则 + 词边界已覆盖已知绕过手法，但这是猫鼠游戏，P0 清单里它依然是"大"工作量项；
- **部署层只读授权还没落地**：demo 态连的是本地 H2（sa 全权限），生产部署时必须建只读账号——这写在部署清单里，属第 14 章内容；
- 集成测试用的是 H2，方言差异（比如 PG 的 `::` 类型转换）要靠 PostgreSQL 环境的冒烟补。

下一篇把测试体系铺开：核心模块的测试策略、哪些值得测、哪些不值得。

---

- 本章对应源码：`SqlGuard.java`、`SqlQueryTool.java`、`BizTools.buildToolkit()`、`src/test/java/com/agent/biz/tool/`
- 配套文档：`docs/03_模块设计/03_03_biz_tools.md`
