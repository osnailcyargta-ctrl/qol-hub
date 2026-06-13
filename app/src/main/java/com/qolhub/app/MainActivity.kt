package com.qolhub.app

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "qol_prefs"
        const val KEY_TIMER_VALUE = "timer_value"
        const val KEY_TIMER_UNIT = "timer_unit"
        const val KEY_BATTERY_PERCENT = "battery_percent"
        const val KEY_FOCUS_MINUTES = "focus_minutes"
        const val KEY_FOCUS_PACKAGE = "focus_package"
        const val KEY_FOCUS_APP_NAME = "focus_app_name"
        const val KEY_CLIPBOARD_JSON = "clipboard_json"
        const val REQUEST_ADMIN = 1001
        const val REQUEST_APP_PICK = 1002
        const val MAX_CLIPBOARD = 30
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: android.content.ComponentName

    // Focus
    private var focusPackage = ""
    private lateinit var tvFocusAppName: TextView
    private lateinit var tvFocusAppPkg: TextView
    private lateinit var ivFocusAppIcon: ImageView

    // Clipboard
    private val clipboardItems = mutableListOf<Pair<String, Long>>() // text, timestamp
    private lateinit var lvClipboard: ListView
    private lateinit var tvClipboardEmpty: TextView
    private lateinit var clipAdapter: ClipboardAdapter

    // ScreenTime
    private lateinit var llTop3: LinearLayout
    private lateinit var llSearchResult: LinearLayout
    private lateinit var tvSearchAppName: TextView
    private lateinit var ivSearchIcon: ImageView
    private lateinit var tvSearchFg: TextView
    private lateinit var tvSearchBg: TextView
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
        setupBatteryAlert()
        setupFocusMode()
        setupClipboard()
        setupScreenTime()
    }

    override fun onResume() {
        super.onResume()
        readCurrentClipboard()
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

    // ── Battery Alert ─────────────────────────────────────────────
    private fun setupBatteryAlert() {
        val etBattery   = findViewById<EditText>(R.id.et_battery_percent)
        val btnBatStart = findViewById<Button>(R.id.btn_battery_start)
        val btnBatStop  = findViewById<Button>(R.id.btn_battery_stop)
        etBattery.setText(prefs.getInt(KEY_BATTERY_PERCENT, 80).toString())
        btnBatStart.setOnClickListener {
            val pct = etBattery.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 80
            prefs.edit().putInt(KEY_BATTERY_PERCENT, pct).apply()
            val intent = Intent(this, BatteryService::class.java).apply { putExtra("target_percent", pct) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Battery alert set at $pct%!", Toast.LENGTH_SHORT).show()
        }
        btnBatStop.setOnClickListener {
            stopService(Intent(this, BatteryService::class.java))
            Toast.makeText(this, "Battery alert stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Focus Mode ────────────────────────────────────────────────
    private fun setupFocusMode() {
        tvFocusAppName = findViewById(R.id.tv_focus_app_name)
        tvFocusAppPkg  = findViewById(R.id.tv_focus_app_pkg)
        ivFocusAppIcon = findViewById(R.id.iv_focus_app_icon)
        val etMinutes     = findViewById<EditText>(R.id.et_focus_minutes)
        val btnPickApp    = findViewById<Button>(R.id.btn_pick_app)
        val btnFocusStart = findViewById<Button>(R.id.btn_focus_start)
        val btnFocusStop  = findViewById<Button>(R.id.btn_focus_stop)

        focusPackage = prefs.getString(KEY_FOCUS_PACKAGE, "") ?: ""
        val savedName = prefs.getString(KEY_FOCUS_APP_NAME, "") ?: ""
        if (focusPackage.isNotEmpty()) {
            tvFocusAppName.text = savedName
            tvFocusAppPkg.text  = focusPackage
            try { ivFocusAppIcon.setImageDrawable(packageManager.getApplicationIcon(focusPackage)) } catch (_: Exception) {}
        }
        etMinutes.setText(prefs.getInt(KEY_FOCUS_MINUTES, 25).toString())

        btnPickApp.setOnClickListener {
            startActivityForResult(Intent(this, AppPickerActivity::class.java), REQUEST_APP_PICK)
        }

        btnFocusStart.setOnClickListener {
            if (!hasUsageStatsPermission()) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                Toast.makeText(this, "Grant Usage Access permission", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (focusPackage.isEmpty()) {
                Toast.makeText(this, "Pick an app first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mins = etMinutes.text.toString().toIntOrNull() ?: 25
            prefs.edit().putString(KEY_FOCUS_PACKAGE, focusPackage).putInt(KEY_FOCUS_MINUTES, mins).apply()
            val intent = Intent(this, FocusService::class.java).apply {
                putExtra("blocked_package", focusPackage)
                putExtra("duration_ms", mins * 60_000L)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            Toast.makeText(this, "Focus mode active for $mins min!", Toast.LENGTH_SHORT).show()
        }

        btnFocusStop.setOnClickListener {
            stopService(Intent(this, FocusService::class.java))
            Toast.makeText(this, "Focus mode stopped.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Clipboard ─────────────────────────────────────────────────
    private fun setupClipboard() {
        lvClipboard      = findViewById(R.id.lv_clipboard)
        tvClipboardEmpty = findViewById(R.id.tv_clipboard_empty)
        loadClipboardFromPrefs()
        clipAdapter = ClipboardAdapter(this, clipboardItems)
        lvClipboard.adapter = clipAdapter
        updateClipboardEmpty()

        lvClipboard.setOnItemClickListener { _, _, pos, _ ->
            val text = clipboardItems[pos].first
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("clip", text))
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        }

        lvClipboard.setOnItemLongClickListener { _, _, pos, _ ->
            clipboardItems.removeAt(pos)
            clipAdapter.notifyDataSetChanged()
            saveClipboardToPrefs()
            updateClipboardEmpty()
            true
        }

        findViewById<Button>(R.id.btn_clipboard_clear).setOnClickListener {
            clipboardItems.clear()
            clipAdapter.notifyDataSetChanged()
            saveClipboardToPrefs()
            updateClipboardEmpty()
            Toast.makeText(this, "Clipboard cleared.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readCurrentClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this).toString().trim()
        if (text.isEmpty()) return
        if (clipboardItems.any { it.first == text }) return
        clipboardItems.add(0, Pair(text, System.currentTimeMillis()))
        if (clipboardItems.size > MAX_CLIPBOARD) clipboardItems.removeAt(clipboardItems.size - 1)
        clipAdapter.notifyDataSetChanged()
        saveClipboardToPrefs()
        updateClipboardEmpty()
    }

    private fun updateClipboardEmpty() {
        if (clipboardItems.isEmpty()) {
            lvClipboard.visibility = View.GONE
            tvClipboardEmpty.visibility = View.VISIBLE
        } else {
            lvClipboard.visibility = View.VISIBLE
            tvClipboardEmpty.visibility = View.GONE
        }
    }

    private fun saveClipboardToPrefs() {
        val sb = StringBuilder()
        clipboardItems.forEach { sb.append(it.second).append("|||").append(it.first).append("~~~") }
        prefs.edit().putString(KEY_CLIPBOARD_JSON, sb.toString()).apply()
    }

    private fun loadClipboardFromPrefs() {
        clipboardItems.clear()
        val raw = prefs.getString(KEY_CLIPBOARD_JSON, "") ?: ""
        if (raw.isEmpty()) return
        raw.split("~~~").forEach { entry ->
            if (entry.isBlank()) return@forEach
            val idx = entry.indexOf("|||")
            if (idx < 0) return@forEach
            val ts   = entry.substring(0, idx).toLongOrNull() ?: return@forEach
            val text = entry.substring(idx + 3)
            if (text.isNotEmpty()) clipboardItems.add(Pair(text, ts))
        }
    }

    // ── Screen Time ───────────────────────────────────────────────
    private fun setupScreenTime() {
        llTop3                  = findViewById(R.id.ll_top3)
        llSearchResult          = findViewById(R.id.ll_search_result)
        tvSearchAppName         = findViewById(R.id.tv_search_app_name)
        ivSearchIcon            = findViewById(R.id.iv_search_icon)
        tvSearchFg              = findViewById(R.id.tv_search_fg)
        tvSearchBg              = findViewById(R.id.tv_search_bg)
        tvScreentimeNoPermission = findViewById(R.id.tv_screentime_no_permission)

        val etSearch  = findViewById<EditText>(R.id.et_screentime_search)
        val btnSearch = findViewById<Button>(R.id.btn_screentime_search)

        if (!hasUsageStatsPermission()) {
            tvScreentimeNoPermission.visibility = View.VISIBLE
            tvScreentimeNoPermission.setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            return
        }

        loadTop3()

        btnSearch.setOnClickListener {
            val query = etSearch.text.toString().trim().lowercase()
            if (query.isEmpty()) return@setOnClickListener
            searchApp(query)
        }
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
            val icon: Drawable? = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }

            val row = LayoutInflater.from(this).inflate(R.layout.item_screentime_row, llTop3, false)
            row.findViewById<TextView>(R.id.tv_rank).text = "#${idx + 1}"
            row.findViewById<TextView>(R.id.tv_st_app_name).text = appName
            row.findViewById<TextView>(R.id.tv_st_fg).text = "Foreground: ${formatDuration(fg)}"
            row.findViewById<TextView>(R.id.tv_st_bg).text = "Background: ${formatDuration(bg)}"
            if (icon != null) row.findViewById<ImageView>(R.id.iv_st_icon).setImageDrawable(icon)
            llTop3.addView(row)
        }
    }

    private fun searchApp(query: String) {
        if (!hasUsageStatsPermission()) return
        val pm = packageManager
        val stats = getUsageStats()
        val match = stats.entries.firstOrNull { pkg ->
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg.key, 0)).toString().lowercase() } catch (_: Exception) { "" }
            name.contains(query)
        }

        if (match == null) {
            Toast.makeText(this, "App not found or no usage data.", Toast.LENGTH_SHORT).show()
            llSearchResult.visibility = View.GONE
            return
        }

        val pkg = match.key
        val (fg, bg) = match.value
        val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        val icon: Drawable? = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }

        tvSearchAppName.text = appName
        if (icon != null) ivSearchIcon.setImageDrawable(icon)
        tvSearchFg.text = formatDuration(fg)
        tvSearchBg.text = formatDuration(bg)
        llSearchResult.visibility = View.VISIBLE
    }

    private fun getUsageStats(): Map<String, Pair<Long, Long>> {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, System.currentTimeMillis())
        val result = mutableMapOf<String, Pair<Long, Long>>()
        stats?.forEach { s ->
            val fg = s.totalTimeInForeground
            // Background = totalTime visible to us; Android doesn't expose background directly
            // We approximate: if totalTimeVisible exists use it, else 0
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

    // ── Helpers ───────────────────────────────────────────────────
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode   = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_APP_PICK && resultCode == RESULT_OK && data != null) {
            val name = data.getStringExtra("app_name") ?: ""
            val pkg  = data.getStringExtra("app_package") ?: ""
            focusPackage = pkg
            tvFocusAppName.text = name
            tvFocusAppPkg.text  = pkg
            prefs.edit().putString(KEY_FOCUS_APP_NAME, name).putString(KEY_FOCUS_PACKAGE, pkg).apply()
            try { ivFocusAppIcon.setImageDrawable(packageManager.getApplicationIcon(pkg)) } catch (_: Exception) {}
        }
    }
}

// ── Clipboard Adapter ─────────────────────────────────────────────
class ClipboardAdapter(private val ctx: android.content.Context, private val items: List<Pair<String, Long>>) :
    BaseAdapter() {
    private val sdf = SimpleDateFormat("HH:mm dd/MM", Locale.getDefault())
    override fun getCount() = items.size
    override fun getItem(pos: Int) = items[pos]
    override fun getItemId(pos: Int) = pos.toLong()
    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_clipboard, parent, false)
        val (text, ts) = items[pos]
        row.findViewById<TextView>(R.id.tv_clip_text).text = text
        row.findViewById<TextView>(R.id.tv_clip_time).text = sdf.format(Date(ts))
        return row
    }
}
