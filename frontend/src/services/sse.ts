/**
 * SSE (Server-Sent Events) 实时事件服务
 * 与后端 /api/events 端点建立长连接，接收实时更新
 *
 * 特性:
 * - 自动重连（指数退避，最多 10 次）
 * - 事件订阅/取消订阅
 * - 连接状态管理
 */

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8082/api'

export type SseEventType = 'connected' | 'stats_update' | 'heartbeat'
export type SseEventHandler = (data: any) => void

class SseService {
  private eventSource: EventSource | null = null
  private handlers = new Map<SseEventType, Set<SseEventHandler>>()
  private reconnectAttempts = 0
  private maxReconnectAttempts = 10
  private reconnectTimer: number | null = null
  private _connected = false
  private statusListeners = new Set<(connected: boolean) => void>()

  get connected(): boolean {
    return this._connected
  }

  /**
   * 建立连接
   */
  connect(): void {
    if (this.eventSource) {
      return  // 已连接
    }

    try {
      this.eventSource = new EventSource(`${API_BASE}/events`)

      this.eventSource.onopen = () => {
        console.log('[SSE] 连接已建立')
        this.reconnectAttempts = 0
        this.setConnected(true)
      }

      this.eventSource.onerror = () => {
        console.warn('[SSE] 连接错误')
        this.setConnected(false)
        this.close()
        this.scheduleReconnect()
      }

      // 注册已订阅的事件
      this.handlers.forEach((_, eventType) => {
        this.attachListener(eventType)
      })
    } catch (e) {
      console.error('[SSE] 创建连接失败', e)
      this.scheduleReconnect()
    }
  }

  /**
   * 订阅事件
   */
  on(eventType: SseEventType, handler: SseEventHandler): void {
    if (!this.handlers.has(eventType)) {
      this.handlers.set(eventType, new Set())
    }
    this.handlers.get(eventType)!.add(handler)

    // 如果已连接，立即附加监听器
    if (this.eventSource) {
      this.attachListener(eventType)
    }
  }

  /**
   * 取消订阅
   */
  off(eventType: SseEventType, handler: SseEventHandler): void {
    this.handlers.get(eventType)?.delete(handler)
  }

  /**
   * 监听连接状态变化
   */
  onStatusChange(listener: (connected: boolean) => void): void {
    this.statusListeners.add(listener)
  }

  /**
   * 断开连接
   */
  disconnect(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.close()
    this.setConnected(false)
  }

  private attachListener(eventType: SseEventType): void {
    if (!this.eventSource) return
    this.eventSource.addEventListener(eventType, (event: MessageEvent) => {
      let data: any = {}
      try {
        data = JSON.parse(event.data)
      } catch {
        // 忽略非 JSON 数据
      }
      this.handlers.get(eventType)?.forEach(handler => {
        try {
          handler(data)
        } catch (e) {
          console.error(`[SSE] 事件处理器错误 (${eventType})`, e)
        }
      })
    })
  }

  private close(): void {
    if (this.eventSource) {
      this.eventSource.close()
      this.eventSource = null
    }
  }

  private setConnected(connected: boolean): void {
    if (this._connected !== connected) {
      this._connected = connected
      this.statusListeners.forEach(l => l(connected))
    }
  }

  /**
   * 指数退避重连: 1s, 2s, 4s, 8s ... 最多 60s
   */
  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.warn('[SSE] 达到最大重连次数，停止重连')
      return
    }
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 60000)
    this.reconnectAttempts++
    console.log(`[SSE] ${delay / 1000}s 后重连 (第 ${this.reconnectAttempts} 次)`)
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, delay)
  }
}

export const sseService = new SseService()
