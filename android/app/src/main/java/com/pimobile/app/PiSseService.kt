package com.pimobile.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 后台 SSE 监听服务（specialUse 前台服务）。
 *
 * 连接 pi-web 的 `/api/agent/running/events`，监听正在运行的 session 集合：
 * - 任务开始：`runningSessionIds` 从空 → 非空
 * - 任务结束：`runningSessionIds` 从非空 → 空（3s 去抖动后发通知）
 *
 * specialUse FGS 不受 Android 15 的 dataSync 6h 限制，适合长期保活 SSE 长连接。
 * 仅在 App 退到后台时启动（ProcessLifecycleOwner.onStop），回到前台时停止。
 */
class PiSseService : Service() {

    private var client: OkHttpClient? = null
    private var eventSource: EventSource? = null
    private var hadRunning = false
    private var lastRunningIds: Set<String> = emptySet()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingFinishNotify: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 启动即转为前台服务，保活 SSE 连接
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val baseUrl = getSharedPreferences("pi-mobile", Context.MODE_PRIVATE)
            .getString("url", null)
        if (baseUrl.isNullOrBlank()) {
            Log.d(TAG, "no saved url, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        connectSse(baseUrl)
        return START_STICKY
    }

    private fun connectSse(baseUrl: String) {
        // 规范化 base url，拼接 /api/agent/running/events
        val endpoint = baseUrl.trimEnd('/') + "/api/agent/running/events"
        Log.d(TAG, "connecting SSE: $endpoint")

        val c = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // SSE 长连接不超时
            .retryOnConnectionFailure(true)
            .build()
        client = c

        val req = Request.Builder().url(endpoint).build()
        eventSource = EventSources.createFactory(c).newEventSource(req, object : EventSourceListener() {
            override fun onEvent(es: EventSource, event: String?, id: String?, data: String) {
                try {
                    val json = JSONObject(data)
                    if (json.optString("type") == "running") {
                        val ids = json.optJSONArray("runningSessionIds")
                        val idSet = mutableSetOf<String>()
                        if (ids != null) for (i in 0 until ids.length()) idSet.add(ids.getString(i))
                        onRunningStateChanged(idSet.isNotEmpty(), idSet)
                    }
                } catch (e: Exception) {
                    // 非 JSON 帧（如心跳注释）忽略
                }
            }

            override fun onClosed(es: EventSource) {
                Log.d(TAG, "SSE closed")
            }

            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                Log.w(TAG, "SSE failure: ${t?.message} code=${response?.code}")
            }
        })
    }

    /**
     * 状态机：非空→空（持续 3s 仍空）= 任务完成，发通知。
     * 去抖动避免短暂中间态误报。
     */
    private fun onRunningStateChanged(running: Boolean, ids: Set<String>) {
        Log.d(TAG, "running=$running hadRunning=$hadRunning ids=$ids")
        if (running) {
            lastRunningIds = ids
            hadRunning = true
            pendingFinishNotify?.let { handler.removeCallbacks(it) }
            pendingFinishNotify = null
        } else if (hadRunning) {
            val finishedIds = lastRunningIds
            pendingFinishNotify?.let { handler.removeCallbacks(it) }
            val r = Runnable {
                // 在后台线程拉会话信息（避免 NetworkOnMainThread）
                Thread { notifyTaskFinished(finishedIds) }.start()
                hadRunning = false
                lastRunningIds = emptySet()
                pendingFinishNotify = null
            }
            pendingFinishNotify = r
            handler.postDelayed(r, 3000)
        }
    }

    /**
     * 拉取刚结束的会话信息，取 firstMessage（用户原始 prompt）作为通知正文，
     * 让用户知道「哪个任务」完成了。返回 (label, sessionId)，拉取失败则返回 (null, null)。
     */
    private fun fetchSessionInfo(ids: Set<String>): Pair<String?, String?> {
        if (ids.isEmpty()) return null to null
        val c = client ?: return null to null
        val baseUrl = getSharedPreferences("pi-mobile", Context.MODE_PRIVATE)
            .getString("url", null) ?: return null to null
        return try {
            val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/sessions").build()
            c.newCall(req).execute().use { resp ->
                val arr = JSONObject(resp.body!!.string()).optJSONArray("sessions") ?: return null to null
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val sid = s.optString("id")
                    if (ids.contains(sid)) {
                        val first = s.optString("firstMessage", "")
                        val label = when {
                            first.isNotBlank() && first != "(no messages)" -> first.replace('\n', ' ').take(50)
                            s.optString("name").isNotBlank() -> s.optString("name").take(50)
                            else -> s.optString("cwd", "").substringAfterLast('/').ifBlank { null }
                        }
                        return label to sid
                    }
                }
                null to null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch session info failed: ${e.message}")
            null to null
        }
    }

    private fun notifyTaskFinished(ids: Set<String>) {
        val (label, sessionId) = fetchSessionInfo(ids)
        val text = label ?: "任务完成"
        Log.d(TAG, "task finished → notify: $text (session=$sessionId)")
        // 带 session deep link，点击直接打开刚结束的会话（pi-web AppShell 读 ?session=）
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) data = android.net.Uri.parse("pi-mobile://session/$sessionId")
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pi Mobile")
            .setContentText("✅ $text")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, n)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "任务完成通知", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun startForegroundCompat() {
        // specialUse FGS 在 Android 14+ 需要声明 type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_FG_ID, foregroundNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_FG_ID, foregroundNotification())
        }
    }

    private fun foregroundNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pi Mobile")
            .setContentText("监听 pi-web 任务状态…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        eventSource?.cancel()
        client?.dispatcher?.executorService?.shutdown()
        pendingFinishNotify?.let { handler.removeCallbacks(it) }
        Log.d(TAG, "service destroyed")
    }

    companion object {
        private const val TAG = "PiSseService"
        private const val CHANNEL_ID = "pi-mobile-task-v2"
        private const val NOTIF_ID = 1001
        private const val NOTIF_FG_ID = 1002

        fun start(ctx: Context) {
            val i = Intent(ctx, PiSseService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PiSseService::class.java))
        }
    }
}
