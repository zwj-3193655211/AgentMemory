package com.agentmemory.service;

import org.slf4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时服务基类
 * 封装定时任务执行的公共逻辑
 */
public abstract class ScheduledServiceBase {
    
    protected Logger log;
    protected ScheduledExecutorService scheduler;
    
    /**
     * 获取服务名称（用于日志和线程名）
     */
    protected abstract String getServiceName();
    
    /**
     * 获取初始延迟（单位：秒）
     */
    protected abstract long getInitialDelaySeconds();
    
    /**
     * 获取执行周期（单位：秒）
     */
    protected abstract long getPeriodSeconds();
    
    /**
     * 执行具体的任务
     */
    protected abstract void executeTask();
    
    /**
     * 启动定时服务
     */
    public void start() {
        log = getLogger();
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, getServiceName());
            t.setDaemon(true);
            return t;
        });
        
        scheduler.scheduleAtFixedRate(
            this::runTask,
            getInitialDelaySeconds(),
            getPeriodSeconds(),
            TimeUnit.SECONDS
        );
        
        log.info("{} 已启动 (初始延迟: {}s, 周期: {}s)", 
            getServiceName(), getInitialDelaySeconds(), getPeriodSeconds());
    }
    
    /**
     * 运行任务（包含异常处理）
     */
    private void runTask() {
        try {
            executeTask();
        } catch (Exception e) {
            log.error("{} 执行失败", getServiceName(), e);
        }
    }
    
    /**
     * 停止定时服务
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
            log.info("{} 已停止", getServiceName());
        }
    }
    
    /**
     * 子类重写此方法返回自己的 Logger
     */
    protected Logger getLogger() {
        return org.slf4j.LoggerFactory.getLogger(getClass());
    }
}
