package com.qolhub.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "qol_prefs"
        const val KEY_TIMER_VALUE = "timer_value"
        const val KEY_TIMER_UNIT = "timer_unit"
        const val KEY_BATTERY_PERCENT = "battery_percent"
        const val KEY_FOCUS_MINUTES = "focus_minutes"
        const val KEY_FOCUS_PACKAGE = "focus_package"
        const val REQUEST_ADMIN = 1001
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)

        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Needed for screen lock feature.")
            }
            startActivityForResult(intent, REQUEST_ADMIN)
        }

        setupScreenTimer()
        setupBatteryAlert()
        setupFocusMode()
    }

    private fun setupScreenTimer() {
        val etTimerValue = findViewById<EditText>(R.id.et_timer_value)
        val spinnerUnit  = findViewById<Spinner>(R.id.spinner_timer_unit)
        val btnStart     = findViewById<Button>(R.id.btn_timer_start)
        val btnStop      = findViewById<Button>(R.id.btn_timer_stop)

        ArrayAdapter.createFromResource(this, R.array.time_units, android.R.layout.simple_spinner_item).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerUnit.adapter = it
        }

        etTimerValue.setText(prefs.getInt(KEY_TIMER_VALUE, 30).toString())
        spinnerUnit.setSelection(prefs.getInt(KEY_TIMER_UNIT, 0))

        btnStart.setOnClickListener {
            val value = etTimerValue.text.toString().toIntOrNull() ?: 30
            val unit  = spinnerUnit.selectedItemPosition
            prefs.edit().putInt(KEY_TIMER_VALUE, value).putInt(KEY_TIMER_UNIT, unit).apply()
            val millis = if (unit == 0) value * 1000L else value * 60_000L
            val intent = Intent(this, ScreenTimerService::class.java).apply {
                putExtra("duration_ms", millis)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            Toast.makeText(this, "Screen timer started!", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenTimerService::class.java))
            Toast.makeText(this, "Timer stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBatteryAlert() {
        val etBattery   = findViewById<EditText>(R.id.et_battery_percent)
        val btnBatStart = findViewById<Button>(R.id.btn_battery_start)
        val btnBatStop  = findViewById<Button>(R.id.btn_battery_stop)

        etBattery.setText(prefs.getInt(KEY_BATTERY_PERCENT, 80).toString())

        btnBatStart.setOnClickListener {
            val pct = etBattery.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 80
            prefs.edit().putInt(KEY_BATTERY_PERCENT, pct).apply()
            val intent = Intent(this, BatteryService::class.java).apply {
                putExtra("target_percent", pct)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            Toast.makeText(this, "Battery alert set at $pct%!", Toast.LENGTH_SHORT).show()
        }

        btnBatStop.setOnClickListener {
            stopService(Intent(this, BatteryService::class.java))
            Toast.makeText(this, "Battery alert stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFocusMode() {
        val etPackage     = findViewById<EditText>(R.id.et_focus_package)
        val etMinutes     = findViewById<EditText>(R.id.et_focus_minutes)
        val btnFocusStart = findViewById<Button>(R.id.btn_focus_start)
        val btnFocusStop  = findViewById<Button>(R.id.btn_focus_stop)

        etPackage.setText(prefs.getString(KEY_FOCUS_PACKAGE, ""))
        etMinutes.setText(prefs.getInt(KEY_FOCUS_MINUTES, 25).toString())

        btnFocusStart.setOnClickListener {
            if (!hasUsageStatsPermission()) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(this, "Please grant Usage Access permission", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val pkg  = etPackage.text.toString().trim()
            val mins = etMinutes.text.toString().toIntOrNull() ?: 25
            if (pkg.isEmpty()) {
                Toast.makeText(this, "Enter app package name!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_FOCUS_PACKAGE, pkg).putInt(KEY_FOCUS_MINUTES, mins).apply()
            val intent = Intent(this, FocusService::class.java).apply {
                putExtra("blocked_package", pkg)
                putExtra("duration_ms", mins * 60_000L)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            Toast.makeText(this, "Focus mode active for $mins min!", Toast.LENGTH_SHORT).show()
        }

        btnFocusStop.setOnClickListener {
            stopService(Intent(this, FocusService::class.java))
            Toast.makeText(this, "Focus mode stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
