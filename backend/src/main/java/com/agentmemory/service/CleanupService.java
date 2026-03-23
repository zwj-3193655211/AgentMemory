package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 数据清理服务
 * 定时清理过期数据，支持软删除和物理删除
 * 使用 ScheduledServiceBase 简化定时任务管理
 */
public class CleanupService extends ScheduledServiceBase {
    
    private final DatabaseService databaseService;
    private final int retentionDays;
    private final int hardDeleteDays;
    private final Logger log = LoggerFactory.getLogger(CleanupService.class);
    
    public CleanupService(DatabaseService databaseService, int retentionDays) {
        this.databaseService = databaseService;
        this.retentionDays = retentionDays;
        this.hardDeleteDays = retentionDays + 7; // 软删除后7天物理删除
    }
    
    @Override
    protected String getServiceName() {
        return "CleanupService";
    }
    
    @Override
    protected long getInitialDelaySeconds() {
        // 计算到下一个凌晨3点的延迟
        return calculateInitialDelay(3);
    }
    
    @Override
    protected long getPeriodSeconds() {
        // 每天执行一次
        return TimeUnit.DAYS.toSeconds(1);
    }
    
    @Override
    protected Logger getLogger() {
        return log;
    }
    
    @Override
    protected void executeTask() {
        log.info("开始清理过期数据...");
        
        try {
            // 1. 软删除过期数据
            int softDeleted = softDeleteExpired();
            log.info("软删除: {} 条记录", softDeleted);
            
            // 2. 物理删除超过保留期的数据
            int hardDeleted = hardDeleteOld();
            log.info("物理删除: {} 条记录", hardDeleted);
            
            // 3. 清理记忆库过期数据
            int memoryDeleted = cleanupMemoryTables();
            log.info("记忆库清理: {} 条记录", memoryDeleted);
            
            log.info("清理完成: 软删除 {}, 物理删除 {}, 记忆库 {}", 
                softDeleted, hardDeleted, memoryDeleted);
            
        } catch (Exception e) {
            log.error("清理失败", e);
        }
    }
    
    /**
     * 立即执行清理（供手动调用）
     */
    public void cleanup() {
        executeTask();
    }
    
    /**
     * 软删除过期的会话和消息
     */
    private int softDeleteExpired() {
        int total = 0;
        total += executeUpdate("UPDATE sessions SET deleted = true WHERE expires_at < NOW() AND deleted = false", "软删除 sessions");
        total += executeUpdate("UPDATE messages SET deleted = true WHERE expires_at < NOW() AND deleted = false", "软删除 messages");
        return total;
    }
    
    /**
     * 物理删除超过保留期的数据
     */
    private int hardDeleteOld() {
        int total = 0;
        total += executeUpdate("DELETE FROM messages WHERE deleted = true AND expires_at < NOW() - INTERVAL '" + hardDeleteDays + " days'", "物理删除 messages");
        total += executeUpdate("DELETE FROM sessions WHERE deleted = true AND expires_at < NOW() - INTERVAL '" + hardDeleteDays + " days'", "物理删除 sessions");
        return total;
    }
    
    /**
     * 清理记忆库过期数据
     */
    private int cleanupMemoryTables() {
        int total = 0;
        total += executeUpdate("DELETE FROM error_corrections WHERE expires_at < NOW() AND deleted = true", "清理 error_corrections");
        total += executeUpdate("DELETE FROM best_practices WHERE expires_at < NOW() AND deleted = true", "清理 best_practices");
        return total;
    }
    
    /**
     * 执行更新 SQL 的公共方法（抽取重复代码）
     */
    private int executeUpdate(String sql, String operationName) {
        try (Connection conn = databaseService.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            return stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("{} 失败", operationName, e);
            return 0;
        }
    }
    
    /**
     * 计算到指定小时的延迟秒数
     */
    private long calculateInitialDelay(int targetHour) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextRun = now.withHour(targetHour).withMinute(0).withSecond(0);
        
        if (now.isAfter(nextRun)) {
            nextRun = nextRun.plusDays(1);
        }
        
        return java.time.Duration.between(now, nextRun).getSeconds();
    }
}
