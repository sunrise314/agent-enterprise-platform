# 06_01 环境准备（Env Prepare）

> 本机 / 服务器运行前需准备的环境。

## 6.1.1 依赖清单

| 组件 | 版本要求 | 用途 |
|---|---|---|
| JDK | 17+ | AgentScope-Java 硬性要求 |
| Maven | 3.8+ | 构建 |
| Docker / Docker Compose | 20.10+ / v2 | 容器化部署 |
| PostgreSQL | 14+ | 业务数据库 |
| Redis | 6+ | 会话 / 限流 |
| Milvus | 2.4+ | 向量库 |
| OpenSearch | 2.x | BM25 检索 |

## 6.1.2 本地快速安装（Windows）

- JDK17：安装 Oracle / Temurin JDK17，配置 `JAVA_HOME`
- Maven：解压配置 `MAVEN_HOME`
- Docker Desktop：安装并启用（PostgreSQL / Redis / Milvus / OpenSearch 用容器）

```powershell
# 验证
java -version
mvn -version
docker --version
```

## 6.1.3 模型服务准备

- 方式一：云端 API（通义千问 / OpenAI），开发调试用
- 方式二：私有化（Ollama / vLLM 部署本地模型），生产内网用

**Ollama 本地部署示例：**
```bash
ollama pull qwen2.5:14b
ollama serve
```

## 6.1.4 环境变量 / 配置外置

通过环境变量注入（不硬编码在代码）：
| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_USER` / `DB_PASS` | PostgreSQL |
| `REDIS_HOST` / `REDIS_PORT` | Redis |
| `MILVUS_HOST` / `MILVUS_PORT` | Milvus |
| `LLM_BASE_URL` / `LLM_API_KEY` | 模型服务 |
| `MODEL_NAME` | 默认模型 |
| `OS_HOST` | OpenSearch |

## 6.1.5 常见问题

- **端口冲突**：`docker ps` 检查占用，改映射端口
- **Milvus 内存不足**：确保 >= 8G 内存，或缩 index 参数
- **模型拉取失败**：检查网络 / 私有仓库配置
