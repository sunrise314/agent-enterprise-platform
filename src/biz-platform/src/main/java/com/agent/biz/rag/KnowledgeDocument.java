package com.agent.biz.rag;

/**
 * 知识文档单元。
 *
 * @param id       文档/片段 ID
 * @param title    标题（用于溯源引用）
 * @param content  正文
 * @param tenantId 所属租户（多租户隔离依据，检索必须按此过滤）
 */
public record KnowledgeDocument(String id, String title, String content, String tenantId) {
}
