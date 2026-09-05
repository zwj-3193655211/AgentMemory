package com.agentmemory.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 从数据库 llm_providers 表加载 LLM Provider 配置并应用到 LLMClient。
 * 供 MemoryService / SessionCompressionService 等共用，避免重复实现。
 */
public final class LLMConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(LLMConfigLoader.class);

    private LLMConfigLoader() {}

    /**
     * 加载启用的默认 LLM Provider 配置并应用到客户端。
     *
     * @param llmClient       待配置的 LLM 客户端
     * @param databaseService 数据库服务（用于读取 llm_providers 表）
     * @return 是否成功应用了配置；失败时客户端保持默认配置（本地 embedding_service）
     */
    public static boolean applyFromDatabase(LLMClient llmClient, DatabaseService databaseService) {
        try (Connection conn = databaseService.getConnection()) {
            // 优先使用默认 Provider，如果没有则查找启用的第一个
            String sql = "SELECT provider_name, base_url, api_key, model, config FROM llm_providers " +
                    "WHERE enabled = true ORDER BY is_default DESC, id ASC LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String providerName = rs.getString("provider_name");
                    String baseUrl = rs.getString("base_url");
                    String apiKey = rs.getString("api_key");
                    String model = rs.getString("model");
                    String configJson = rs.getString("config");

                    // 解析 config 中的 thinkMode
                    boolean thinkMode = false;
                    if (configJson != null && !configJson.isEmpty()) {
                        try {
                            JsonNode configNode = new ObjectMapper().readTree(configJson);
                            thinkMode = configNode.path("thinkMode").asBoolean(false);
                        } catch (Exception e) {
                            log.warn("解析 config JSON 失败", e);
                        }
                    }

                    // 如果是本地模型，baseUrl 可能为 null，使用本地 Ollama
                    if (baseUrl == null || baseUrl.isEmpty()) {
                        baseUrl = "http://localhost:11434";
                        providerName = "ollama";
                    }

                    llmClient.setProvider(providerName, baseUrl, apiKey, model, thinkMode);
                    log.info("加载 LLM 配置: provider={}, model={}, thinkMode={}", providerName, model, thinkMode);
                    return true;
                }
            }
            log.warn("未找到启用的 LLM Provider，使用默认配置");
        } catch (SQLException e) {
            log.warn("加载 LLM 配置失败，使用默认配置", e);
        }
        return false;
    }
}
