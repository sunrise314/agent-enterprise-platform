# 06_04 备份与恢复

> 保证数据安全与故障可恢复。

## 6.4.1 备份范围

| 数据 | 位置 | 备份方式 |
|---|---|---|
| 业务数据 / 评估 / 审计 | PostgreSQL | pg_dump |
| 向量数据 | Milvus | 快照 / 全量导出 |
| 关键词索引 | OpenSearch | snapshot |
| 文档原文 | 文件存储 | 文件备份 |
| 会话缓存 | Redis | 可丢失（非核心，重建即可） |

## 6.4.2 PostgreSQL 备份

```bash
# 备份
pg_dump -h <host> -U agent -d agent_platform -F c -f backup_$(date +%Y%m%d).dump

# 恢复
pg_restore -h <host> -U agent -d agent_platform --clean backup_20241010.dump
```

## 6.4.3 备份策略（建议）

- 每日全量备份（PostgreSQL / 文件）
- 保留最近 7 天，周期归档
- 异地 / 离线副本（内网安全要求）
- 每月恢复演练一次，验证备份可用

## 6.4.4 恢复流程

1. 停掉 biz-platform 写入流量（或维护模式）
2. 恢复 PostgreSQL dump
3. 恢复 Milvus / OpenSearch 快照
4. 校验数据一致性（表行数、关键记录）
5. 恢复服务，验证核心接口

## 6.4.5 注意事项

- 审计日志只追加，备份必须保留（合规要求）
- 评估指标为历史数据，备份后支持历史回归对比
- 备份文件加密存储（含敏感数据）
