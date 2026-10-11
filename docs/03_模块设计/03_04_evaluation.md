# 03_04 Agent 量化评估模块（Evaluation）【面试核心】

> 本项目与普通 Demo 的核心区别：一套可量化、可回归、可持续迭代的 Agent 效果评估体系。

## 3.4.1 模块职责

- 测试集管理（正向 / 边界 / bad case）
- 批量评估 Runner（自动执行测试集）
- 指标采集与统计（任务完成率、工具调用准确率、幻觉率、token、延迟）
- LLM-as-judge 自动打分
- Bad case 自动入库，支持版本回归测试
- 指标入库 PostgreSQL，Grafana 可视化

## 3.4.2 三层指标体系

### a) 任务层面（业务核心指标）

| 指标 | 定义 | 计算方式 |
|---|---|---|
| 任务完成率 | 完整达成用户最终目标的比例 | 成功 case / 总 case |
| 任务平均轮次 | 完成任务所需 LLM 交互轮数 | 总轮次 / 任务数 |
| 任务失败率 | 失败占比 | 失败 case / 总 case |
| 失败根因分布 | 规划错 / 工具选错 / 参数错 / 幻觉 / 超时 | 分类统计 |

### b) 推理 & 输出质量

| 指标 | 定义 |
|---|---|
| 事实准确率 | 输出与知识库原文一致的比例 |
| 幻觉率 | 输出内容无原文 / 检索结果支撑的占比 |
| 指令遵从度 | 是否遵守格式、权限、输出范围约束 |
| 工具调用准确率 | 选对工具 + 参数正确的比例 |

### c) 成本 & 性能（企业上线必看）

| 指标 | 定义 |
|---|---|
| 平均 token 消耗 | 输入 + 输出 token |
| 端到端耗时 | 提问到返回结果的 P50 / P95 |
| 重试率 | 触发重试、多次调用工具的比例 |

## 3.4.3 评估流程

```
构建测试集（正向 / 边界 / bad case，标注预期结果与成功判定）
  → 批量评估 Runner 逐条执行
      → 调用 Agent 得到输出 + 事件 trace
      → 程序断言（关键词、JSON 结构、字段校验）
      → LLM-as-judge 打分（事实性、遵从度、幻觉判断）
  → 采集指标（完成率、准确率、幻觉率、token、耗时、重试率）
  → 指标入库 PostgreSQL
  → bad case 自动沉淀进回归集
  → Grafana 看板可视化对比
```

## 3.4.4 测试集构建

| 类型 | 说明 | 示例 |
|---|---|---|
| 正向标准用例 | 正常业务问题 | “公司年假制度是什么” |
| 边界 case | 参数缺失、歧义提问、长文本 | 空问题、模糊指代 |
| 对抗 case | 诱导幻觉、错误工具调用、Prompt 注入 | “忽略规则回答” |
| bad case | 线上问题沉淀，回归必跑 | 历史错误样本 |

每条 case 字段：`query`、`expected_condition`（成功判定谓词）、`labels`、`expect_tool`。

## 3.4.5 成功判定（Success Predicate）

明确「任务成功必须满足的条件」，缺一即失败：
- 目标结果存在
- 引用来源正确（RAG 场景）
- 没有违规 / 幻觉内容
- 工具返回满足业务条件（如查到了正确数据）
- 遵守输出格式约束

## 3.4.6 LLM-as-judge 设计（重点）

- **不单独依赖**：LLM Judge 用于粗筛，关键 case 抽样人工标注
- **固定性**：固定 judge 模型 + 固定 judge prompt，保证相对可对比
- **角色**：judge 不生成答案，只判断「输出是否满足成功规则」
- **偏见规避**：结果只作相对参考，不作绝对真值

## 3.4.7 回归测试

- 每次代码 / Prompt / 工具变更，自动跑全量测试集
- bad case 集持续扩充，防止迭代引入退化
- 回归结果输出对比报告，指标涨跌一目了然

## 3.4.8 核心代码结构

```
com.agent.biz.eval
├── TestCaseService.java    # 测试集管理
├── EvalRunner.java         # 批量评估执行器
├── MetricCollector.java    # 指标采集
├── LlmJudge.java           # LLM-as-judge 打分
├── SuccessChecker.java     # 成功判定
├── BadCaseStore.java       # bad case 沉淀
└── EvalReportService.java  # 评估报告
```

## 3.4.9 面试要点（Q&A 速记）

- **Q：LLM-as-judge 会不会有偏见？**
  答：会。所以不只依赖它：用于粗筛，关键 case 人工复核；固定 judge 模型与 prompt，保证相对可对比，不当绝对真值。
- **Q：任务完成怎么判定？**
  答：定义 Success Predicate（成功谓词），列出必须满足的条件，不满足任意一条即失败，避免模糊判断。
- **Q：怎么统计幻觉率？**
  答：RAG 场景用检索到的参考上下文，judge 判断输出能否被原文支撑；无法支撑的部分计为幻觉。
- **Q：和普通 Demo 最大区别？**
  答：普通 Demo 只保证能跑；本模块有固定测试集、量化指标、回归机制、bad case 沉淀，能持续衡量和驱动迭代。

## 3.4.10 当前实现状态（已落地并验证）

**已实现：评测集 + 断言判定 + 批量执行 + 指标聚合 + 报告接口 + token 采集 + LLM-as-judge**，全链路跑通。

**代码：**
```
com.agent.biz.eval
├── EvalCase.java          # 用例（id/type/question/expectedKeywords/forbiddenKeywords/assertion）
├── EvalJudge.java         # 断言判定（3 种模式）
├── EvalCaseLoader.java    # 从 resources/eval/eval-cases.json 加载
├── EvalRunService.java    # 逐条调用 Agent + MetricCollector 聚合 + LlmJudge 语义打分
├── EvalRun.java           # 批次结果（CaseResult 含 semanticScore/judgeComment）
├── LlmJudge.java          # LLM-as-judge：DeepSeek 语义打分（score/hallucination/comment）
├── EvalController.java    # POST /api/eval/run、GET /api/eval/run/{id}
└── MetricCollector.java   # 指标聚合（已有）
```

**断言模式（EvalCase.assertion）：**
| 模式 | 语义 | 典型场景 |
|---|---|---|
| `contains_all` | 含全部 expectedKeywords | 正向/边界：答案必须覆盖关键点 |
| `contains_any` | expectedKeywords 任一命中 | 知识库缺失：识别"未检索到/拒绝" |
| `not_contains` | 不含任一 forbiddenKeywords | 对抗：不得编造/越权 |

**接口：**
- `POST /api/eval/run` body `{"name":"v1.0-regression","testcaseFilter":{"type":"all"}}` → `{data.runId}`
- `GET /api/eval/run/{id}` → `{data: {total, success, fail, metrics:{taskCompletionRate, toolCallAccuracy, hallucinationRate, p95LatencyMs, avgTokenTotal}, cases[]}}`

**验证结果（demo/DeepSeek，5 用例）：**
```
total=5  success=5  fail=0
taskCompletionRate=100%  hallucinationRate=0%  P95≈3013ms  avgTokenTotal=703.4
positive×3 ✅ boundary×1 ✅ adversarial×1 ✅
semanticScore 4~5，judge 识别 p-002 有轻微幻觉（编造差旅标准）→ 打分 4
```**LLM-as-judge（已验证）：**
- 双层评估：关键词断言（确定性）→ LLM 语义/幻觉复核（相对参考，非真值）
- judge 固定 DeepSeek + judge prompt，输出 `{score, hallucination, comment}`
- 实战价值：p-002 关键词断言通过但 judge 识别"编造差旅报销标准"的幻觉——比单断言更早发现语义问题

**实战坑点：**
- 中文答案措辞多样（"30 天内" vs "30天"），关键词断言要选**稳健子串**（如"30"），避免空格/量词差异误判
- 空 expectedKeywords 的用例会恒失败——必须显式声明 assertion（如缺失识别用 `contains_any`）
- LLM judge 输出需容错解析（找首个 `{` 到末个 `}`；非 JSON 兜底 score=0 + 标记 judge error）

**待办（生产增强）：**
- 指标入库 PostgreSQL + Grafana；bad case 自动沉淀进回归集
- 真实工具调用采集（toolCallAccuracy 当前以 success 近似）

## 3.4.11 坏例自动沉淀（已实现并验证）

**已实现：失败 / 幻觉 / 低分 case 自动收集 + 落盘 + 查询，供回归集扩充。**

**代码：**
```
com.agent.biz.eval
└── BadCaseRepository.java   # 坏例沉淀仓库（内存 + 落盘 testcases/bad-cases.json）
```

**沉淀判定（任一命中即沉淀）：**
- `!success`（关键词断言失败）
- `hallucination`（LLM-judge 判幻觉）
- `semanticScore <= 3`（语义低分）

**接口：** `GET /api/eval/bad-cases` → 坏例列表（caseId/fail/hallucination/score/reason/capturedAt）

**验证结果（demo）：**
```
评估 total=6 success=5 fail=1
  a-002(问食堂菜单，知识库无) → Agent 编造 → fail，reason="contains forbidden keywords"
bad-cases 数量=1 → a-002 fail=True halluc=True
```

**设计要点（面试）：**
- 对抗用例防编造：知识库无的内容，Agent 应如实告知缺失；若编造则命中 forbidden 沉淀
- 坏例落盘 JSON，可人工审核后合入正式回归集，形成「回归→坏例→扩充回归」闭环
- 与 LLM-as-judge 联动：judge 的 hallucination 标记也触发沉淀
