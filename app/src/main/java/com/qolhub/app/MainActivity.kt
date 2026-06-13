package com.qolhub.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "qol_prefs"
        const val KEY_TIMER_VALUE = "timer_value"
        const val KEY_TIMER_UNIT = "timer_unit"
        const val REQUEST_ADMIN = 1001
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: android.content.ComponentName

    // ScreenTime
    private lateinit var llTop3: LinearLayout
    private lateinit var tvScreentimeNoPermission: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = android.content.ComponentName(this, DeviceAdminReceiver::class.java)

        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Needed for screen lock feature.")
            }
            startActivityForResult(intent, REQUEST_ADMIN)
        }

        setupScreenTimer()
        setupScreenTime()
    }

    override fun onResume() {
        super.onResume()
        if (hasUsageStatsPermission()) loadTop3()
    }

    // ── Screen Timer ──────────────────────────────────────────────
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
            val intent = Intent(this, ScreenTimerService::class.java).apply { putExtra("duration_ms", millis) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Screen timer started!", Toast.LENGTH_SHORT).show()
        }
        btnStop.setOnClickListener {
            stopService(Intent(this, ScreenTimerService::class.java))
            Toast.makeText(this, "Timer stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Screen Time ───────────────────────────────────────────────
    private fun setupScreenTime() {
        llTop3                   = findViewById(R.id.ll_top3)
        tvScreentimeNoPermission = findViewById(R.id.tv_screentime_no_permission)

        if (!hasUsageStatsPermission()) {
            tvScreentimeNoPermission.visibility = View.VISIBLE
            tvScreentimeNoPermission.setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            return
        }

        loadTop3()
    }

    private fun loadTop3() {
        if (!hasUsageStatsPermission()) return
        val stats = getUsageStats()
        val pm = packageManager
        val top3 = stats.entries
            .filter { it.key != packageName }
            .sortedByDescending { it.value.first }
            .take(3)

        llTop3.removeAllViews()
        top3.forEachIndexed { idx, entry ->
            val pkg = entry.key
            val (fg, bg) = entry.value
            val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }

            val row = LayoutInflater.from(this).inflate(R.layout.item_screentime_row, llTop3, false)
            row.findViewById<TextView>(R.id.tv_rank).text = "#${idx + 1}"
            row.findViewById<TextView>(R.id.tv_st_app_name).text = appName
            row.findViewById<TextView>(R.id.tv_st_fg).text = "Foreground: ${formatDuration(fg)}"
            row.findViewById<TextView>(R.id.tv_st_bg).text = "Background: ${formatDuration(bg)}"
            if (icon != null) row.findViewById<android.widget.ImageView>(R.id.iv_st_icon).setImageDrawable(icon)
            llTop3.addView(row)
        }
    }

    private fun getUsageStats(): Map<String, Pair<Long, Long>> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
        val result = mutableMapOf<String, Pair<Long, Long>>()
        stats?.forEach { s ->
            val fg = s.totalTimeInForeground
            val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s.totalTimeVisible - fg else 0L
            if (fg > 0 || bg > 0) result[s.packageName] = Pair(fg.coerceAtLeast(0L), bg.coerceAtLeast(0L))
        }
        return result
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
