package com.fastfileshare.app

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import com.google.android.material.button.MaterialButton
import java.net.NetworkInterface
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatusValue: TextView
    private lateinit var tvIpValue: TextView
    private lateinit var tvTvLinkValue: TextView
    private lateinit var btnToggleServer: MaterialButton
    private lateinit var btnOpenRemote: MaterialButton

    private val notificationPermissionCode = 202

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatusValue = findViewById(R.id.tvStatusValue)
        tvIpValue = findViewById(R.id.tvIpValue)
        tvTvLinkValue = findViewById(R.id.tvTvLinkValue)
        btnToggleServer = findViewById(R.id.btnToggleServer)
        btnOpenRemote = findViewById(R.id.btnOpenRemote)

        btnToggleServer.setOnClickListener {
            handleToggleButtonClick()
        }

        btnOpenRemote.setOnClickListener {
            val remoteIntent = Intent(this, RemoteActivity::class.java)
            val ip = getLocalIpAddress() ?: "127.0.0.1"
            remoteIntent.putExtra("SERVER_IP", ip)
            startActivity(remoteIntent)
        }

        // Initialize UI State
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
        
        // Refresh IP Address in case network changed (e.g. hotspot enabled)
        val ip = getLocalIpAddress()
        if (ip != null) {
            tvIpValue.text = "http://$ip:8080"
            tvTvLinkValue.text = "http://$ip:8080/client/index.html"
        } else {
            tvIpValue.text = "Connect to Hotspot/Wi-Fi"
            tvTvLinkValue.text = "Connect to Hotspot/Wi-Fi"
        }
    }

    private fun handleToggleButtonClick() {
        // 1. Verify storage permissions
        if (!hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        // 2. Verify notifications permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    notificationPermissionCode
                )
                return
            }
        }

        // 3. Toggle Service state
        val intent = Intent(this, MediaServerService::class.java)
        if (isServiceRunning(MediaServerService::class.java)) {
            stopService(intent)
            Toast.makeText(this, "Server Stopped", Toast.LENGTH_SHORT).show()
        } else {
            startService(intent)
            Toast.makeText(this, "Server Started", Toast.LENGTH_SHORT).show()
        }

        // Give OS a split second to start/stop the service, then refresh UI
        btnToggleServer.postDelayed({
            updateUiState()
        }, 300)
    }

    private fun updateUiState() {
        val running = isServiceRunning(MediaServerService::class.java)
        if (running) {
            tvStatusValue.text = "RUNNING"
            tvStatusValue.setTextColor(Color.parseColor("#10b981")) // Green
            btnToggleServer.text = "Stop Server"
            btnToggleServer.setBackgroundColor(Color.parseColor("#ef4444")) // Red
            btnOpenRemote.visibility = View.VISIBLE
            
            val ip = getLocalIpAddress()
            if (ip != null) {
                tvIpValue.text = "http://$ip:8080"
                tvTvLinkValue.text = "http://$ip:8080/client/index.html"
            }
        } else {
            tvStatusValue.text = "STOPPED"
            tvStatusValue.setTextColor(Color.parseColor("#ef4444")) // Red
            btnToggleServer.text = "Start Server"
            btnToggleServer.setBackgroundColor(Color.parseColor("#d4af37")) // Gold
            btnOpenRemote.visibility = View.GONE
            tvIpValue.text = "http://---.---.---.---:8080"
            tvTvLinkValue.text = "http://---.---.---.---:8080/client/index.html"
        }
    }

    // Helper to check if Foreground Service is currently running
    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    // Check All Files access storage permission (Android 11+)
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            write == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Please grant All Files Access to share files", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                val intent = Intent().apply {
                    action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                }
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                101
            )
        }
    }

    // Resolve non-loopback IPv4 network interface addresses (Hotspot/Wi-Fi interface)
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress) {
                        val ip = address.hostAddress ?: continue
                        // Check if it's an IPv4 address
                        val isIPv4 = ip.indexOf(':') < 0
                        if (isIPv4) {
                            // On mobile hotspot, the standard IP is usually 192.168.43.1
                            // This filter skips cellular data provider IPs to focus on WLAN/Hotspot
                            return ip
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationPermissionCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                handleToggleButtonClick()
            } else {
                Toast.makeText(this, "Notification permission is required to run server in background", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
