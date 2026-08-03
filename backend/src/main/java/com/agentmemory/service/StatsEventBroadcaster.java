package com.agentmemory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE (Server-Sent Events) 实时事件广播服务
 * 用于向前端推送统计数据更新、新消息通知等实时事件
 *
 * 特性:
 * - 事件驱动: 新消息保存时立即推送
 * - 心跳保活: 每 30 秒发送心跳，防止连接超时
 * - 自动清理: 客户端断开后自动移除
 */
public class StatsEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(StatsEventBroadcaster.class);
    private static final StatsEventBroadcaster INSTANCE = new StatsEventBroadcaster();

    /** SSE 客户端连接 */
    private final List<SseClient> clients = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService heartbeatExecutor;
    private final AtomicLong eventCounter = new AtomicLong(0);

    /** 防抖: 事件推送最小间隔（毫秒），避免高频刷新 */
    private static final long MIN_PUSH_INTERVAL_MS = 2000;
    private volatile long lastPushTime = 0;

    private StatsEventBroadcaster() {
        // 心跳任务: 每 30 秒发送一次心跳
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, 30, 30, TimeUnit.SECONDS);
    }

    public static StatsEventBroadcaster getInstance() {
        return INSTANCE;
    }

    /**
     * 注册新的 SSE 客户端
     */
    public void register(SseClient client) {
        clients.add(client);
        log.info("SSE 客户端连接，当前连接数: {}", clients.size());
    }

    /**
     * 新消息事件（由 FileWatcherService 调用）
     */
    public void notifyNewMessage() {
        long now = System.currentTimeMillis();
        // 防抖: 距上次推送不足 2 秒则跳过
        if (now - lastPushTime < MIN_PUSH_INTERVAL_MS) {
            return;
        }
        lastPushTime = now;
        broadcast("stats_update", "{}");
    }

    /**
     * 广播事件到所有客户端
     */
    public void broadcast(String event, String data) {
        if (clients.isEmpty()) {
            return;
        }
        long eventId = eventCounter.incrementAndGet();
        String payload = "id: " + eventId + "\n"
                + "event: " + event + "\n"
                + "data: " + data + "\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        for (SseClient client : clients) {
            try {
                client.send(bytes);
            } catch (IOException e) {
                log.debug("SSE 客户端断开，移除连接");
                clients.remove(client);
            }
        }
    }

    /**
     * 发送心跳（SSE 注释行，保持连接活跃）
     */
    private void sendHeartbeat() {
        if (clients.isEmpty()) {
            return;
        }
        byte[] heartbeat = ": heartbeat\n\n".getBytes(StandardCharsets.UTF_8);
        for (SseClient client : clients) {
            try {
                client.send(heartbeat);
            } catch (IOException e) {
                clients.remove(client);
            }
        }
    }

    public int getClientCount() {
        return clients.size();
    }

    /**
     * SSE 客户端连接封装
     */
    public static class SseClient {
        private final OutputStream outputStream;
        private volatile boolean active = true;

        public SseClient(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        public synchronized void send(byte[] bytes) throws IOException {
            if (!active) {
                throw new IOException("client inactive");
            }
            try {
                outputStream.write(bytes);
                outputStream.flush();
            } catch (IOException e) {
                active = false;
                throw e;
            }
        }

        public boolean isActive() {
            return active;
        }

        public void close() {
            active = false;
        }
    }
}
