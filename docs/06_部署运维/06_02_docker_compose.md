# 06_02 Docker Compose 部署

> 一键启动整套平台（PostgreSQL / Redis / Milvus / OpenSearch / biz-platform）。

## 6.2.1 docker-compose.yml（示例骨架）

```yaml
version: "3.8"

services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: agent_platform
      POSTGRES_USER: agent
      POSTGRES_PASSWORD: ${DB_PASS}
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]

  redis:
    image: redis:7
    ports: ["6379:6379"]

  milvus:
    image: milvusdb/milvus:v2.4.0
    ports: ["19530:19530", "9091:9091"]

  opensearch:
    image: opensearchproject/opensearch:2.x
    environment:
      discovery.type: single-node
      DISABLE_SECURITY_PLUGIN: "true"
    ports: ["9200:9200"]

  biz-platform:
    build: ./src/biz-platform
    depends_on: [postgres, redis, milvus, opensearch]
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/agent_platform
      DB_USER: agent
      DB_PASS: ${DB_PASS}
      REDIS_HOST: redis
      MILVUS_HOST: milvus
      OS_HOST: opensearch
      LLM_BASE_URL: ${LLM_BASE_URL}
      LLM_API_KEY: ${LLM_API_KEY}
      MODEL_NAME: ${MODEL_NAME}
    ports: ["8080:8080"]

volumes:
  pgdata:
```

## 6.2.2 .env 配置

```
DB_PASS=change-me
LLM_BASE_URL=http://host.docker.internal:11434/v1   # Ollama 本地
LLM_API_KEY=local
MODEL_NAME=qwen2.5:14b
```

## 6.2.3 启动 / 停止

```bash
# 启动
docker compose up -d
docker compose logs -f biz-platform

# 停止
docker compose down

# 清数据（慎用）
docker compose down -v
```

## 6.2.4 健康检查

- `GET /actuator/health` 返回 biz-platform 状态
- 依赖服务健康：postgres / redis / milvus / opensearch

## 6.2.5 私有化镜像构建

```bash
docker build -t agent-platform/biz-platform:1.0.0 ./src/biz-platform
# 导出离线镜像包，内网环境导入
docker save agent-platform/biz-platform:1.0.0 -o biz-platform-1.0.0.tar
docker load -i biz-platform-1.0.0.tar
```
