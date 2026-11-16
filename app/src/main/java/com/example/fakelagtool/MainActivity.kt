package com.example.fakelagtool

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.fakelagtool.databinding.ActivityMainBinding
import java.util.ArrayDeque

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val logLines = ArrayDeque<String>()
    private var dropCount = 0
    private var pendingStart = false

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && pendingStart) {
            startVpnService()
        }
        pendingStart = false
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore result: service still runs without notif on API 33+ */ }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getStringExtra(FakeLagVpnService.EXTRA_TYPE)) {
                FakeLagVpnService.TYPE_STATUS -> {
                    val on = intent.getBooleanExtra(FakeLagVpnService.EXTRA_RUNNING, false)
                    applyUiState(on)
                }
                FakeLagVpnService.TYPE_LOG -> {
                    val line = intent.getStringExtra(FakeLagVpnService.EXTRA_LINE) ?: return
                    appendLog(line)
                }
                FakeLagVpnService.TYPE_COUNT -> {
                    dropCount = intent.getIntExtra(FakeLagVpnService.EXTRA_COUNT, dropCount)
                    b.tvDropCount.text = "Đã drop: $dropCount gói"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val prefs = getSharedPreferences(FakeLagVpnService.PREFS, MODE_PRIVATE)
        b.etMin.setText(prefs.getInt(FakeLagVpnService.KEY_MIN, 50).toString())
        b.etMax.setText(prefs.getInt(FakeLagVpnService.KEY_MAX, 250).toString())

        b.btnToggle.setOnClickListener { onToggleClicked() }
        b.btnGrantVpn.setOnClickListener { requestVpnPermission(startAfter = false) }

        applyUiState(FakeLagVpnService.isRunning)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver, IntentFilter(FakeLagVpnService.ACTION_EVENT)
        )
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        super.onStop()
    }

    private fun onToggleClicked() {
        if (FakeLagVpnService.isRunning) {
            stopVpnService()
        } else {
            val prep = VpnService.prepare(this)
            if (prep != null) {
                pendingStart = true
                vpnPermLauncher.launch(prep)
            } else {
                saveByteRange()
                startVpnService()
            }
        }
    }

    private fun requestVpnPermission(startAfter: Boolean) {
        val prep = VpnService.prepare(this) ?: run {
            b.tvVpnStatus.text = "VPN: đã có quyền"
            return
        }
        pendingStart = startAfter
        vpnPermLauncher.launch(prep)
    }

    private fun saveByteRange() {
        val minV = b.etMin.text.toString().toIntOrNull()?.coerceIn(0, 1500) ?: 50
        val maxV = b.etMax.text.toString().toIntOrNull()?.coerceIn(0, 1500) ?: 250
        val fixedMax = if (maxV < minV) minV else maxV
        getSharedPreferences(FakeLagVpnService.PREFS, MODE_PRIVATE)
            .edit()
            .putInt(FakeLagVpnService.KEY_MIN, minV)
            .putInt(FakeLagVpnService.KEY_MAX, fixedMax)
            .apply()
        b.etMin.setText(minV.toString())
        b.etMax.setText(fixedMax.toString())
    }

    private fun startVpnService() {
        saveByteRange()
        val i = Intent(this, FakeLagVpnService::class.java)
            .setAction(FakeLagVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
    }

    private fun stopVpnService() {
        val i = Intent(this, FakeLagVpnService::class.java)
            .setAction(FakeLagVpnService.ACTION_STOP)
        startService(i)
    }

    private fun applyUiState(on: Boolean) {
        if (on) {
            b.root.setBackgroundColor(0xFF10241A.toInt())
            b.btnToggle.text = "●  FAKE LAG: ON"
            b.btnToggle.setBackgroundColor(0xFF2ECC71.toInt())
            b.tvVpnStatus.text = "VPN: ON"
            b.tvVpnStatus.setTextColor(0xFF2ECC71.toInt())
            b.tvTitle.setTextColor(0xFF2ECC71.toInt())
        } else {
            b.root.setBackgroundColor(0xFF1E1E2E.toInt())
            b.btnToggle.text = "FAKE LAG: OFF"
            b.btnToggle.setBackgroundColor(0xFF6C7086.toInt())
            b.tvVpnStatus.text = "VPN: OFF"
            b.tvVpnStatus.setTextColor(0xFF6C7086.toInt())
            b.tvTitle.setTextColor(0xFFB0B4C0.toInt())
        }
        b.tvDropCount.text = "Đã drop: $dropCount gói"
    }

    private fun appendLog(line: String) {
        logLines.addLast(line)
        while (logLines.size > 5) logLines.removeFirst()
        b.tvLog.text = logLines.joinToString("\n")
    }
}
