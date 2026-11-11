package com.venice.ffloader

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

data class Entity(
    val sx: Float, val sy: Float, val w: Float, val h: Float, val dist: Float,
    val headX: Float, val headY: Float, val bodyX: Float, val bodyY: Float,
    val hp: Int, val id: Int
)

object EspState { @Volatile var entities: List<Entity> = emptyList() }

object Cfg {
    @Volatile var esp = true
    @Volatile var box = true
    @Volatile var line = true
    @Volatile var info = true
    @Volatile var dot = true
    @Volatile var aim = false
    @Volatile var aimOnHead = true
    @Volatile var aimFov = 220f
    @Volatile var smooth = 2.2f
    @Volatile var headOffsetY = -4f
}

class EspView(ctx: Context) : View(ctx) {
    private val box = Paint(1).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(0, 255, 140) }
    private val line = Paint(1).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(150, 0, 255, 140) }
    private val txt = Paint(1).apply { textSize = 28f; color = Color.WHITE; typeface = Typeface.MONOSPACE }
    private val bar = Paint(1).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.rgb(255, 80, 80) }
    private val fov = Paint(1).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(80, 255, 255, 255) }
    private val head = Paint(1).apply { style = Paint.Style.FILL; color = Color.rgb(255, 220, 0) }

    override fun onDraw(c: Canvas) {
        if (!Cfg.esp) return
        val cx = width / 2f
        if (Cfg.aim) c.drawCircle(cx, height / 2f, Cfg.aimFov, fov)
        for (e in EspState.entities) {
            if (Cfg.box) {
                c.drawRect(e.sx, e.sy, e.sx + e.w, e.sy + e.h, box)
                val f = e.hp.coerceIn(0, 200) / 200f
                c.drawLine(e.sx - 9f, e.sy + e.h, e.sx - 9f, e.sy + e.h - e.h * f, bar)
            }
            if (Cfg.dot) c.drawCircle(e.headX, e.headY, 5f, head)
            if (Cfg.line) c.drawLine(cx, height.toFloat(), e.sx + e.w / 2f, e.sy + e.h, line)
            if (Cfg.info) {
                c.drawText("${e.dist.toInt()}m", e.sx, e.sy - 8f, txt)
                c.drawText("${e.hp}", e.sx + e.w + 6f, e.sy + 22f, txt)
            }
        }
    }
}

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var view: EspView? = null
    private var job: Job? = null
    private var busy = false

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notif())

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = EspView(this)
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        wm.addView(v, lp)
        view = v
        job = CoroutineScope(Dispatchers.Default).launch { loop() }
    }

    private fun notif(): Notification {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("esp", "ESP", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this, "esp")
            .setContentTitle("ESP running").setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
    }

    private suspend fun loop() {
        while (isActive) {
            val e = EngineHolder.engine
            if (e == null) { delay(200); continue }
            val dm = resources.displayMetrics
            val b = runCatching { e.poll(dm.widthPixels, dm.heightPixels) }.getOrNull()
            if (b != null && b.size >= 16) parse(b)
            delay(16)
        }
    }

    private suspend fun parse(b: ByteArray) {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int != 0x46534550) return
        val n = bb.int.coerceIn(0, 64)
        val sw = bb.int; val sh = bb.int
        val out = ArrayList<Entity>(n)
        for (i in 0 until n) {
            if (bb.remaining() < 44) break
            val sx = bb.float; val sy = bb.float; val w = bb.float; val h = bb.float
            val d = bb.float; val hx = bb.float; val hy = bb.float; val bx = bb.float; val by = bb.float
            val hp = bb.int; val id = bb.int
            out += Entity(sx, sy, w, h, d, hx, hy, bx, by, hp, id)
        }
        EspState.entities = out
        withContext(Dispatchers.Main) { view?.invalidate() }
        aim(out, sw, sh)
    }

    private fun aim(list: List<Entity>, sw: Int, sh: Int) {
        if (!Cfg.aim || busy || list.isEmpty()) return
        val cx = sw / 2f; val cy = sh / 2f
        fun px(e: Entity) = if (Cfg.aimOnHead) e.headX else e.bodyX
        fun py(e: Entity) = if (Cfg.aimOnHead) e.headY + Cfg.headOffsetY else e.bodyY

        val best = list.map { it to hypot(px(it) - cx, py(it) - cy) }
            .filter { it.second <= Cfg.aimFov }
            .minByOrNull { it.second * 0.6f + it.first.dist * 2f }?.first ?: return
        val dx = px(best) - cx; val dy = py(best) - cy
        if (hypot(dx, dy) < 10f) return

        busy = true
        CoroutineScope(Dispatchers.Default).launch {
            runCatching {
                EngineHolder.engine?.touch(cx, cy, cx + dx / Cfg.smooth, cy + dy / Cfg.smooth, 8, 70)
            }
            delay(140)
            busy = false
        }
    }

    override fun onDestroy() {
        job?.cancel()
        view?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var logView: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 56, 48, 56) }
        fun btn(t: String, f: () -> Unit) = root.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        fun sw(t: String, g: () -> Boolean, s: (Boolean) -> Unit) = root.addView(Switch(this).apply {
            text = t; isChecked = g(); setOnCheckedChangeListener { _, v -> s(v) }
        })
        logView = TextView(this).apply { textSize = 12f; typeface = Typeface.MONOSPACE }

        btn("1. Install Shizuku") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"))) }
        }
        btn("2. Grant Shizuku permission") { ShizukuMgr.request() }
        btn("3. Allow overlay") {
            if (!Settings.canDrawOverlays(this))
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else log("already allowed")
        }
        btn("4. Bind engine") {
            if (!ShizukuMgr.granted()) { log("grant Shizuku first"); return@btn }
            EngineHolder.bind(this)
            CoroutineScope(Dispatchers.Main).launch {
                delay(1500); log(if (EngineHolder.bound) "engine bound" else "NOT bound - tap 4 again")
            }
        }
        btn("5. Self-test") {
            val e = EngineHolder.engine ?: return@btn log("bind first")
            val pkg = listOf(Names.GAME, Names.GAME_MAX).firstOrNull {
                runCatching { packageManager.getPackageInfo(it, 0); true }.getOrDefault(false)
            } ?: return@btn log("Free Fire not installed")
            log("--- test ---")
            log(runCatching { if (e.start(pkg)) "START OK" else "START FAILED" }.getOrElse { "EX ${it.message}" })
            log(runCatching { e.info() }.getOrElse { "info failed" })
        }
        btn("6. Start overlay") {
            if (!Settings.canDrawOverlays(this)) return@btn log("allow overlay first")
            startForegroundService(Intent(this, OverlayService::class.java)); log("overlay on")
        }
        btn("Stop all") {
            stopService(Intent(this, OverlayService::class.java))
            runCatching { EngineHolder.engine?.stop() }; log("stopped")
        }
        btn("Copy log") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("log", logView.text)); log("copied")
        }

        sw("ESP", { Cfg.esp }, { Cfg.esp = it })
        sw("Boxes", { Cfg.box }, { Cfg.box = it })
        sw("Head dot", { Cfg.dot }, { Cfg.dot = it })
        sw("Snaplines", { Cfg.line }, { Cfg.line = it })
        sw("Distance / HP", { Cfg.info }, { Cfg.info = it })
        sw("AIMBOT", { Cfg.aim }, { Cfg.aim = it })
        sw("Aim at head", { Cfg.aimOnHead }, { Cfg.aimOnHead = it })

        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun log(t: String) { logView.append("$t\n") }
    override fun onDestroy() { if (isFinishing) EngineHolder.unbind(); super.onDestroy() }
}
