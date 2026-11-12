package com.webrtc.scrcpy.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.webrtc.scrcpy.core.input.AccessibilityInputInjector
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"
    private val REQUEST_MEDIA_PROJECTION = 101

    private lateinit var tvStatusTitle: TextView
    private lateinit var tvUrlTip: TextView
    private lateinit var tvAccessUrl: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnAccessibility: Button

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RemoteControlService.ACTION_STATE_CHANGED) {
                val isRunning = intent.getBooleanExtra(RemoteControlService.EXTRA_IS_RUNNING, false)
                Log.i(TAG, "Received service status broadcast: isRunning=$isRunning")
                updateUiState(isRunning)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        IceServerStore.applyFromIntent(this, intent)

        tvStatusTitle = findViewById(R.id.tv_status_title)
        tvUrlTip = findViewById(R.id.tv_url_tip)
        tvAccessUrl = findViewById(R.id.tv_access_url)
        btnToggle = findViewById(R.id.btn_toggle_service)
        btnAccessibility = findViewById(R.id.btn_open_accessibility)

        btnToggle.setOnClickListener {
            if (RemoteControlService.isRunning) {
                btnToggle.isEnabled = false
                btnToggle.text = "Stopping service..."
                stopRemoteService()
            } else {
                btnToggle.isEnabled = false
                btnToggle.text = "Requesting screen capture permission..."
                requestMediaProjection()
            }
        }

        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        }

        updateUiState(RemoteControlService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RemoteControlService.ACTION_STATE_CHANGED)
        registerReceiver(statusReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                btnToggle.text = "Starting remote control service..."
                startRemoteService(data)
            } else {
                Log.w(TAG, "User denied or canceled screen capture permission")
                updateUiState(false)
            }
        }
    }

    private fun startRemoteService(resultData: Intent) {
        val intent = Intent(this, RemoteControlService::class.java).apply {
            action = RemoteControlService.ACTION_START
            putExtra(RemoteControlService.EXTRA_RESULT_DATA, resultData)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopRemoteService() {
        val intent = Intent(this, RemoteControlService::class.java).apply {
            action = RemoteControlService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState(isRunning: Boolean) {
        btnToggle.isEnabled = true
        val accessibilityEnabled = AccessibilityInputInjector.serviceInstance != null
        btnAccessibility.text = if (accessibilityEnabled) {
            "Accessibility Service Enabled (Rootless Touch Ready)"
        } else {
            "Enable Accessibility Service (for Rootless Touch)"
        }
        if (isRunning) {
            tvStatusTitle.text = getString(R.string.service_running)
            btnToggle.text = getString(R.string.stop_service)
            tvUrlTip.visibility = View.VISIBLE
            tvAccessUrl.visibility = View.VISIBLE

            val ip = getLocalIpAddress() ?: "127.0.0.1"
            tvAccessUrl.text = "http://$ip:8080/?token=${AccessTokenStore.get(this)}"
        } else {
            tvStatusTitle.text = getString(R.string.service_stopped)
            btnToggle.text = getString(R.string.start_service)
            tvUrlTip.visibility = View.GONE
            tvAccessUrl.visibility = View.GONE
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        IceServerStore.applyFromIntent(this, intent)
    }

    override fun onResume() {
        super.onResume()
        updateUiState(RemoteControlService.isRunning)
    }
}
