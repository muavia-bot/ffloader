package com.venice.ffloader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Edit ONLY this object when a game update breaks the ESP. */
object Names {
    const val GAME = "com.dts.freefireth"
    const val GAME_MAX = "com.dts.freefiremax"
    const val FACADE = "GameFacade"
    val FACADE_STATIC = listOf("Instance", "s_Instance", "m_Instance")
    val FACADE_PLAYERMANAGER = listOf("PlayerManager", "m_PlayerManager", "playerManager")
    val FACADE_CAMERA = listOf("MainCamera", "m_MainCamera", "Camera")
    const val PLAYER = "Player"
    val PLAYER_DEAD = listOf("m_IsDead", "isDead")
    val PLAYER_AVATAR = listOf("m_Avatar", "m_AvatarManager", "avatar")
    val PLAYER_HP = listOf("m_Hp", "m_Health")
    val PLAYER_ID = listOf("m_PlayerID", "m_Id")
    const val AVATAR = "Avatar"
    val AVATAR_HEAD = listOf("m_HeadPos", "m_HeadPosition", "headPos")
    val AVATAR_ROOT = listOf("m_Pos", "m_Position", "m_RootPos")
    const val CAMERA = "Camera"
    val CAM_VIEW = listOf("m_WorldToCameraMatrix", "worldToCameraMatrix")
    val CAM_PROJ = listOf("m_ProjectionMatrix", "projectionMatrix")
    val PLAYER_LIST = listOf("m_Players", "m_List", "players")
    const val MAX_DISTANCE = 250f
}

class Mem(pid: Int) : AutoCloseable {
    private val raf = RandomAccessFile("/proc/$pid/mem", "r")
    fun read(a: Long, n: Int): ByteArray {
        val b = ByteArray(n)
        synchronized(raf) { raf.seek(a); raf.readFully(b) }
        return b
    }
    fun chunk(a: Long, n: Int): ByteArray? = try { read(a, n) } catch (e: Exception) { null }
    fun u64(a: Long) = try { ByteBuffer.wrap(read(a, 8)).order(ByteOrder.LITTLE_ENDIAN).long } catch (e: Exception) { 0L }
    fun u32(a: Long) = try { ByteBuffer.wrap(read(a, 4)).order(ByteOrder.LITTLE_ENDIAN).int } catch (e: Exception) { 0 }
    fun u8(a: Long) = try { read(a, 1)[0].toInt() and 0xff } catch (e: Exception) { 0 }
    fun f32(a: Long) = try { ByteBuffer.wrap(read(a, 4)).order(ByteOrder.LITTLE_ENDIAN).float } catch (e: Exception) { 0f }
    fun cstr(a: Long, max: Int = 64): String {
        val b = read(a, max); var i = 0
        while (i < b.size && b[i] != 0.toByte()) i++
        return String(b, 0, i)
    }
    override fun close() { runCatching { raf.close() } }
}

class EngineService(ctx: Context) : Shizuku.UserService(ctx) {

    private val binder = object : IEngine.Stub() {
        override fun start(pkg: String) = this@EngineService.start(pkg)
        override fun poll(sw: Int, sh: Int) = this@EngineService.poll(sw, sh)
        override fun touch(a: Float, b: Float, c: Float, d: Float, s: Int, m: Int) =
            this@EngineService.touch(a, b, c, d, s, m)
        override fun tap(x: Float, y: Float) = this@EngineService.tap(x, y)
        override fun info() = this@EngineService.info()
        override fun stop() = this@EngineService.stop()
    }
    override fun onBind(i: Intent?): IBinder = binder

    @Volatile private var running = false
    @Volatile private var frame = ByteArray(0)
    @Volatile private var sw = 2340
    @Volatile private var sh = 1080
    private var mem: Mem? = null
    private var worker: Thread? = null
    private var pid = -1
    private var metaBase = 0L; private var metaOff = 0L; private var metaLen = 0L
    private var strBase = 0L; private var strSize = 0L
    private var nameOff = -1; private var fieldsOff = -1
    private var fieldNameOff = -1; private var fieldOffOff = -1
    private var cFacade = 0L; private var cPlayer = 0L; private var cAvatar = 0L; private var cCamera = 0L
    private var libsCache: List<Map> = emptyList()
    private val diag = StringBuilder()

    @Synchronized
    fun start(pkg: String): Boolean {
        stop(); diag.setLength(0)
        pid = findPid(pkg)
        if (pid <= 0) { diag.append("FAIL: game not running\n"); return false }
        diag.append("pid=$pid\n")
        val m = try { Mem(pid) } catch (e: Exception) {
            diag.append("FAIL: /proc/$pid/mem unreadable\n"); return false
        }
        mem = m
        val maps = loadMaps() ?: run { diag.append("FAIL: maps\n"); return false }
        val meta = maps.firstOrNull { it.path.contains("global-metadata.dat") }
            ?: run { diag.append("FAIL: metadata not mapped\n"); return false }
        metaBase = meta.start; metaOff = meta.fileOff; metaLen = meta.end - meta.start
        if (!parseHeader(m)) { diag.append("FAIL: bad metadata header\n"); return false }
        diag.append("metadata ok\n")
        val libs = maps.filter { it.rw && it.path.contains("libil2cpp.so") }
        if (libs.isEmpty()) { diag.append("FAIL: no libil2cpp rw mapping\n"); return false }
        libsCache = libs
        cFacade = findClass(m, libs, Names.FACADE)
        diag.append("GameFacade=${hex(cFacade)}\n")
        if (cFacade == 0L) { diag.append("FAIL: class not found (names changed?)\n"); return false }
        diag.append("layout ok\n")
        cPlayer = findClass(m, libs, Names.PLAYER)
        cAvatar = findClass(m, libs, Names.AVATAR)
        cCamera = findClass(m, libs, Names.CAMERA)
        diag.append("Player=${hex(cPlayer)} Avatar=${hex(cAvatar)} Camera=${hex(cCamera)}\n")
        if (cPlayer == 0L || cAvatar == 0L || cCamera == 0L) { diag.append("FAIL: dependent class missing\n"); return false }
        val inst = staticObj(m, cFacade, Names.FACADE_STATIC)
        diag.append("Instance=${hex(inst)}\n")
        if (inst == 0L) { diag.append("FAIL: facade null - be in lobby/match\n"); return false }
        diag.append("inject=${if (injector() != null) "binder" else "shell"}\n")
        running = true
        worker = Thread { loop() }.also { it.start() }
        return true
    }

    fun stop() { running = false; worker?.join(800); worker = null; mem?.close(); mem = null }
    fun info(): String = diag.toString()
    fun poll(w: Int, h: Int): ByteArray { if (!running) return ByteArray(0); sw = w; sh = h; return frame }

    private fun findPid(pkg: String): Int {
        for (p in File("/proc").list() ?: return -1) {
            val n = p.toIntOrNull() ?: continue
            val cl = runCatching { File("/proc/$n/cmdline").readText().trimEnd('\u0000') }.getOrNull()
            if (cl == pkg) return n
        }
        return -1
    }

    private class Map(val start: Long, val end: Long, val rw: Boolean, val fileOff: Long, val path: String)

    private fun loadMaps(): List<Map>? = runCatching {
        File("/proc/$pid/maps").readLines().mapNotNull { l ->
            val p = l.split(Regex("\\s+"))
            if (p.size < 5) return@mapNotNull null
            val r = p[0].split('-')
            Map(r[0].toLong(16), r[1].toLong(16), p[1].startsWith("rw"),
                p[2].toLongOrNull() ?: 0L, p.getOrElse(5) { "" })
        }
    }.getOrNull()

    private fun parseHeader(m: Mem): Boolean {
        val hdr = m.chunk(metaBase, 0x120) ?: return false
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        for (o in 0x40 until 0x100 step 4) {
            val off = bb.getInt(o).toLong() and 0xffffffffL
            val size = bb.getInt(o + 4).toLong() and 0xffffffffL
            if (off <= 0x100 || size < 1024 || off + size > metaLen) continue
            val a = metaBase - metaOff + off
            val probe = m.chunk(a, 64) ?: continue
            if (probe.count { it in 32..126 } < 48) continue
            strBase = a; strSize = size; return true
        }
        return false
    }

    private fun strAddr(s: String): Long {
        val n = s.toByteArray(); var a = strBase
        val end = strBase + strSize - n.size
        while (a < end) {
            val len = minOf(1 shl 20, (end - a).toInt() + 1)
            val buf = mem!!.chunk(a, len) ?: return 0
            var i = 0
            while (i + n.size < buf.size) {
                var ok = true
                for (k in n.indices) if (buf[i + k] != n[k]) { ok = false; break }
                if (ok && buf[i + n.size] == 0.toByte()) return a + i
                i++
            }
            a += len
        }
        return 0
    }

    private fun inMeta(p: Long) = p >= metaBase && p < metaBase + metaLen
    private fun inModule(p: Long) = libsCache.any { p >= it.start && p < it.end }

    private fun findClass(m: Mem, libs: List<Map>, name: String): Long {
        val na = strAddr(name)
        if (na == 0L) return 0
        for (r in libs) {
            var a = r.start
            while (a < r.end - 8) {
                val len = minOf(1 shl 20, (r.end - a).toInt())
                if (len < 8) break
                val buf = m.chunk(a, len) ?: break
                var i = 0
                while (i + 8 <= buf.size) {
                    if (ByteBuffer.wrap(buf, i, 8).order(ByteOrder.LITTLE_ENDIAN).long == na) {
                        for (o in 0..0x40 step 8) if (probeClass(m, a + i - o, na)) return a + i - o
                    }
                    i += 8
                }
                a += buf.size
            }
        }
        return 0
    }

    private fun probeClass(m: Mem, cand: Long, na: Long): Boolean {
        if (cand <= 0x1000 || (cand and 7L) != 0L) return false
        val b = m.chunk(cand, 0x1c0) ?: return false
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        for (o in 0..0x40 step 8) {
            if (bb.getLong(o) != na) continue
            if (!inMeta(bb.getLong(o + 8))) continue
            nameOff = o
            for (f in 0x40..0x180 step 8) {
                val arr = bb.getLong(f)
                if (arr <= 0x1000 || (arr and 7L) != 0L || inModule(arr)) continue
                val eb = ByteBuffer.wrap(m.chunk(arr, 0x60) ?: continue).order(ByteOrder.LITTLE_ENDIAN)
                var ok = true
                for (k in 0 until 3) {
                    val base = k * 0x20
                    var hn = -1
                    for (no in intArrayOf(0x08, 0x10)) if (inMeta(eb.getLong(base + no))) hn = no
                    if (hn < 0) { ok = false; break }
                    var ho = -1
                    for (oo in intArrayOf(0x18, 0x20, 0x24)) {
                        val ov = eb.getInt(base + oo)
                        if (ov in 0..0x3fff && ov % 4 == 0) ho = oo
                    }
                    if (ho < 0) { ok = false; break }
                    fieldNameOff = hn; fieldOffOff = ho
                }
                if (ok) { fieldsOff = f; return true }
            }
        }
        return false
    }

    private fun fieldOffset(m: Mem, klass: Long, name: String): Int {
        if (klass == 0L || fieldsOff < 0) return -1
        val arr = m.u64(klass + fieldsOff)
        if (arr <= 0x1000) return -1
        for (i in 0 until 400) {
            val e = arr + i * 0x20L
            val np = m.u64(e + fieldNameOff)
            if (!inMeta(np)) continue
            if (m.cstr(np) != name) continue
            val ov = m.u32(e + fieldOffOff)
            if (ov in 0..0x3fff) return ov
        }
        return -1
    }

    private fun offAny(m: Mem, k: Long, names: List<String>): Int {
        for (n in names) { val o = fieldOffset(m, k, n); if (o >= 0) return o }
        return -1
    }

    private fun fieldObj(m: Mem, obj: Long, k: Long, names: List<String>): Long {
        if (obj == 0L) return 0
        val o = offAny(m, k, names); if (o < 0) return 0
        val p = m.u64(obj + 0x10 + o)
        return if (p > 0x1000 && inModule(m.u64(p))) p else 0
    }

    private fun staticObj(m: Mem, k: Long, names: List<String>): Long {
        val o = offAny(m, k, names); if (o < 0) return 0
        for (s in 0x70..0x220 step 8) {
            val base = m.u64(k + s)
            if (base <= 0x1000 || inModule(base)) continue
            val p = m.u64(base + o)
            if (p > 0x1000 && inModule(m.u64(p))) return p
        }
        return 0
    }

    private fun loop() {
        val m = mem ?: return
        while (running) {
            try { frame = build(m) } catch (e: Exception) { }
            Thread.sleep(16)
        }
    }

    private fun build(m: Mem): ByteArray {
        val out = ByteBuffer.allocate(16 + 64 * 44).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46534550); out.putInt(0); out.putInt(sw); out.putInt(sh)

        val facade = staticObj(m, cFacade, Names.FACADE_STATIC); if (facade == 0L) return out.array()
        val pm = fieldObj(m, facade, cFacade, Names.FACADE_PLAYERMANAGER)
        val cam = fieldObj(m, facade, cFacade, Names.FACADE_CAMERA)
        if (pm == 0L || cam == 0L) return out.array()
        val vt = fieldObj(m, cam, cCamera, Names.CAM_VIEW)
        val pt = fieldObj(m, cam, cCamera, Names.CAM_PROJ)
        if (vt == 0L || pt == 0L) return out.array()
        val vp = mul(m.chunk(pt + 0x10, 64) ?: return out.array(), m.chunk(vt + 0x10, 64) ?: return out.array())

        val list = fieldObj(m, pm, cPlayer, Names.PLAYER_LIST); if (list == 0L) return out.array()
        val items = m.u64(list + 0x10); val size = m.u32(list + 0x18)
        if (items == 0L || size <= 0 || size > 64) return out.array()

        val deadOff = offAny(m, cPlayer, Names.PLAYER_DEAD)
        val hpOff = offAny(m, cPlayer, Names.PLAYER_HP)
        val idOff = offAny(m, cPlayer, Names.PLAYER_ID)
        var count = 0

        for (i in 0 until size) {
            val pl = m.u64(items + 0x20 + i * 8L); if (pl <= 0x1000) continue
            if (deadOff >= 0 && m.u8(pl + 0x10 + deadOff) != 0) continue
            val av = fieldObj(m, pl, cPlayer, Names.PLAYER_AVATAR); if (av == 0L) continue
            val h = fieldObj(m, av, cAvatar, Names.AVATAR_HEAD)
            val r = fieldObj(m, av, cAvatar, Names.AVATAR_ROOT)
            if (h == 0L || r == 0L) continue

            val hx = m.f32(h + 0x10); val hy = m.f32(h + 0x14); val hz = m.f32(h + 0x18)
            val rx = m.f32(r + 0x10); val ry = m.f32(r + 0x14); val rz = m.f32(r + 0x18)
            val dist = sqrt((rx - vp[12]).pow(2) + (ry - vp[13]).pow(2) + (rz - vp[14]).pow(2))
            if (dist > Names.MAX_DISTANCE) continue

            val hs = project(vp, hx, hy, hz) ?: continue
            val bs = project(vp, rx, ry, rz) ?: continue
            val top = min(hs[1], bs[1]); val bot = max(hs[1], bs[1])
            val cx = (hs[0] + bs[0]) * 0.5f
            val w = abs(hs[0] - bs[0]); val hh = abs(bot - top)
            if (w < 4f || hh < 8f) continue

            out.putFloat(cx - w * 0.5f)      // box left
            out.putFloat(top)                // box top
            out.putFloat(w)
            out.putFloat(hh)
            out.putFloat(dist)
            out.putFloat(hs[0])              // HEAD aim point
            out.putFloat(hs[1])
            out.putFloat(cx)                 // body aim point
            out.putFloat(top + hh * 0.38f)
            out.putInt(if (hpOff >= 0) m.u32(pl + 0x10 + hpOff) else 100)
            out.putInt(if (idOff >= 0) m.u32(pl + 0x10 + idOff) else 0)
            if (++count >= 64) break
        }
        out.putInt(4, count)
        return out.array()
    }

    private fun mul(a: ByteArray, b: ByteArray): FloatArray {
        val af = FloatArray(16) { ByteBuffer.wrap(a, it * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float }
        val bf = FloatArray(16) { ByteBuffer.wrap(b, it * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float }
        val o = FloatArray(16)
        for (i in 0 until 4) for (j in 0 until 4) {
            var s = 0f; for (k in 0 until 4) s += af[k * 4 + j] * bf[i * 4 + k]; o[i * 4 + j] = s
        }
        return o
    }

    private fun project(vp: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw < 0.001f) return null
        return floatArrayOf((cx / cw * 0.5f + 0.5f) * sw, (1f - (cy / cw * 0.5f + 0.5f)) * sh)
    }

    private fun injector(): Any? = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrElse {
        runCatching {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        }.getOrNull()
    }

    private val injectMethod: Method? by lazy {
        val i = injector() ?: return@lazy null
        runCatching {
            i.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private fun ev(a: Int, x: Float, y: Float, d: Long) =
        MotionEvent.obtain(d, SystemClock.uptimeMillis(), a, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
            runCatching { javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(this, 0) }
        }

    private fun send(e: MotionEvent): Boolean {
        val m = injectMethod ?: return false
        val i = injector() ?: return false
        return runCatching { (m.invoke(i, e, 0) as? Boolean) ?: true }.getOrDefault(false)
    }

    fun touch(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int, ms: Int): Boolean {
        val d = SystemClock.uptimeMillis()
        if (send(ev(MotionEvent.ACTION_DOWN, x0, y0, d))) {
            val n = steps.coerceIn(1, 20)
            for (i in 1..n) {
                val t = i.toFloat() / n
                send(ev(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, d))
                Thread.sleep((ms / n).toLong().coerceAtLeast(1))
            }
            send(ev(MotionEvent.ACTION_UP, x1, y1, d))
            return true
        }
        val sb = StringBuilder("input motionevent DOWN $x0 $y0; ")
        for (i in 1..6) {
            val t = i / 6f
            sb.append("input motionevent MOVE ${x0 + (x1 - x0) * t} ${y0 + (y1 - y0) * t}; ")
        }
        sb.append("input motionevent UP $x1 $y1")
        return shell(sb.toString())
    }

    fun tap(x: Float, y: Float): Boolean {
        val d = SystemClock.uptimeMillis()
        if (send(ev(MotionEvent.ACTION_DOWN, x, y, d))) {
            send(ev(MotionEvent.ACTION_UP, x, y, d)); return true
        }
        return shell("input tap $x $y")
    }

    private fun shell(c: String) = runCatching {
        Runtime.getRuntime().exec(arrayOf("sh", "-c", c)).waitFor() == 0
    }.getOrDefault(false)

    private fun hex(v: Long) = if (v == 0L) "0" else "0x" + v.toString(16)
}

object ShizukuMgr {
    fun alive() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = runCatching {
        alive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
    fun request() { if (alive() && !granted()) runCatching { Shizuku.requestPermission(4711) } }
}

object EngineHolder {
    @Volatile var engine: IEngine? = null
    @Volatile var bound = false
    private var args: Shizuku.UserServiceArgs? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            engine = IEngine.Stub.asInterface(b); bound = true
        }
        override fun onServiceDisconnected(n: ComponentName) { engine = null; bound = false }
    }
    fun bind(ctx: Context) {
        val a = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, EngineService::class.java.name))
            .daemon(false).processNameSuffix("engine").debuggable(false).version(1)
        args = a
        runCatching { Shizuku.bindUserService(a, conn) }
    }
    fun unbind() {
        args?.let { runCatching { Shizuku.bindUserService(a, conn) }
