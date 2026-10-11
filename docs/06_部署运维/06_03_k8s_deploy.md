# 06_03 K8s 生产部署（v1.2）

> 高可用集群部署方案（生产 / 政企加分项）。

## 6.3.1 部署架构

```
           ┌── Ingress ──┐
           │             │
        biz-platform   biz-platform   (多副本，HPA 水平扩展)
           │             │
        ──── 依赖服务（可外部化 / 托管）────
        PostgreSQL / Redis / Milvus / OpenSearch
```

## 6.3.2 Deployment 示例

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: biz-platform
spec:
  replicas: 3
  selector:
    matchLabels: { app: biz-platform }
  template:
    metadata:
      labels: { app: biz-platform }
    spec:
      containers:
        - name: biz-platform
          image: agent-platform/biz-platform:1.0.0
          ports: [{ containerPort: 8080 }]
          envFrom:
            - configMapRef: { name: agent-config }
            - secretRef: { name: agent-secret }
          resources:
            requests: { cpu: "1", memory: "2Gi" }
            limits: { cpu: "2", memory: "4Gi" }
          livenessProbe:
            httpGet: { path: /actuator/health, port: 8080 }
```

## 6.3.3 高可用要点

- **多副本 + HPA**：按 CPU / 请求数水平扩展
- **无状态设计**：会话状态在 Redis（外部），业务数据在 PG，Agent 实例可随时增减
- **Readiness 探针**：依赖就绪才接流量
- **滚动更新**：`strategy.type: RollingUpdate`，零停机升级

## 6.3.4 ConfigMap / Secret

- ConfigMap：非敏感配置（模型名、URL）
- Secret：DB 密码、API Key

## 6.3.5 可观测接入

- Prometheus Operator 采集 `/actuator/prometheus`
- Grafana 面板：延迟 P95、错误率、token、完成率
- 日志：Filebeat / Loki 采集 JSON 日志

## 6.3.6 面试要点

- **Q：为什么 Agent 服务可以做多副本无状态？**
  答：会话状态外置 Redis、业务数据在 PG、向量在 Milvus，Agent 实例不持有本地状态，因此可水平扩展、滚动升级。
