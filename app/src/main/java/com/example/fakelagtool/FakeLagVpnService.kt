package com.example.fakelagtool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FakeLagVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.fakelagtool.START"
        const val ACTION_STOP = "com.example.fakelagtool.STOP"
        const val ACTION_EVENT = "com.example.fakelagtool.EVENT"
        const val EXTRA_TYPE = "type"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_LINE = "line"
        const val EXTRA_COUNT = "count"
        const val TYPE_STATUS = "status"
        const val TYPE_LOG = "log"
        const val TYPE_COUNT = "count"

        const val PREFS = "fakelag_prefs"
        const val KEY_MIN = "min_byte"
        const val KEY_MAX = "max_byte"

        const val CHANNEL_ID = "fakelag_vpn"
        const val NOTIF_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private val dropCount = AtomicLong(0)
    private val writeLock = Any()
    private var tunOutput: FileOutputStream? = null

    private lateinit var executor: ThreadPoolExecutor
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        executor = Executors.newFixedThreadPool(8) as ThreadPoolExecutor
        executor.maximumPoolSize = 8
        executor.setKeepAliveTime(30, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (running.get()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val minB = prefs.getInt(KEY_MIN, 50)
        val maxB = prefs.getInt(KEY_MAX, 250)

        val builder = Builder()
            .setSession("FakeLagTool")
            .setMtu(1500)
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")

        try {
            tunFd = builder.establish()
        } catch (e: Exception) {
            broadcastLog("VPN establish failed: ${e.message}")
            stopSelf()
            return
        }
        if (tunFd == null) {
            broadcastLog("VPN establish returned null")
            stopSelf()
            return
        }

        startForegroundCompat()
        running.set(true)
        isRunning = true
        dropCount.set(0)
        broadcastStatus(true)

        worker = Thread({ runLoop(minB, maxB) }, "FakeLagWorker").also { it.start() }
    }

    private fun stopVpn() {
        running.set(false)
        isRunning = false
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
        try { worker?.join(1000) } catch (_: Exception) {}
        worker = null
        executor.shutdownNow()
        broadcastStatus(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runLoop(minB: Int, maxB: Int) {
        val fd = tunFd ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        tunOutput = output
        val buffer = ByteArray(32767)

        try {
            while (running.get()) {
                val len = input.read(buffer)
                if (len <= 0) continue
                val packet = buffer.copyOf(len)
                val ip = PacketUtils.parseIpv4(packet) ?: continue
                when (ip.protocol) {
                    17 -> handleUdp(ip, packet, minB, maxB, output)
                    6 -> handleTcp(ip, packet, output)
                    else -> { /* drop other protocols */ }
                }
            }
        } catch (_: Exception) {
            // TUN closed on stop
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            tunOutput = null
        }
    }

    private fun handleUdp(
        ip: PacketUtils.IpInfo, packet: ByteArray,
        minB: Int, maxB: Int, output: FileOutputStream
    ) {
        val udp = PacketUtils.parseUdp(ip, packet) ?: return
        if (udp.length in minB..maxB) {
            val c = dropCount.incrementAndGet()
            broadcastLog("UDP ${udp.srcPort}→${udp.dstPort} ${udp.length}B DROP")
            if (c % 10L == 0L) broadcastCount(c.toInt())
            return
        }
        // forward outside size range
        executor.execute {
            try {
                val sock = DatagramSocket()
                protect(sock)
                sock.soTimeout = 2000
                val dst = InetAddress.getByAddress(udp.dstIp)
                sock.send(DatagramPacket(udp.payload, udp.payload.size, dst, udp.dstPort))
                val respBuf = ByteArray(32767)
                val resp = DatagramPacket(respBuf, respBuf.size)
                sock.receive(resp)
                sock.close()

                val response = PacketUtils.buildIpv4Udp(
                    srcIp = udp.dstIp,
                    dstIp = udp.srcIp,
                    srcPort = udp.dstPort,
                    dstPort = udp.srcPort,
                    payload = resp.data.copyOf(resp.length)
                )
                synchronized(writeLock) { output.write(response) }
            } catch (_: Exception) {
                // timeout or unreachable: silently drop
            }
        }
    }

    private fun handleTcp(ip: PacketUtils.IpInfo, packet: ByteArray, output: FileOutputStream) {
        val tcp = PacketUtils.parseTcp(ip, packet) ?: return
        // Only respond to fresh SYNs with RST so browsers fail fast instead of hanging.
        if (tcp.syn && !tcp.ackFlag) {
            try {
                val rst = PacketUtils.buildTcpRst(ip, tcp)
                synchronized(writeLock) { output.write(rst) }
            } catch (_: Exception) {}
        }
    }

    // ---------- notification ----------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, "Fake Lag VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "VPN service status"
            mgr.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fake Lag Tool")
            .setContentText("VPN active · dropping UDP ${getPrefsRange()}")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun getPrefsRange(): String {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        return "${p.getInt(KEY_MIN, 50)}–${p.getInt(KEY_MAX, 250)}B"
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------- broadcasts ----------
    private fun broadcastStatus(on: Boolean) {
        val i = Intent(ACTION_EVENT).apply {
            putExtra(EXTRA_TYPE, TYPE_STATUS)
            putExtra(EXTRA_RUNNING, on)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun broadcastLog(line: String) {
        val i = Intent(ACTION_EVENT).apply {
            putExtra(EXTRA_TYPE, TYPE_LOG)
            putExtra(EXTRA_LINE, "[${timeFmt.format(Date())}] $line")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun broadcastCount(c: Int) {
        val i = Intent(ACTION_EVENT).apply {
            putExtra(EXTRA_TYPE, TYPE_COUNT)
            putExtra(EXTRA_COUNT, c)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
