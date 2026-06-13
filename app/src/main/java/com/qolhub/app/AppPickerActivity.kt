package com.qolhub.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

data class AppInfo(val name: String, val packageName: String, val icon: Drawable)

class AppPickerActivity : AppCompatActivity() {

    private lateinit var allApps: List<AppInfo>
    private lateinit var adapter: AppPickerAdapter
    private val filtered = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val etSearch = findViewById<EditText>(R.id.et_app_search)
        val lv = findViewById<ListView>(R.id.lv_apps)

        allApps = loadApps()
        filtered.addAll(allApps)
        adapter = AppPickerAdapter(this, filtered)
        lv.adapter = adapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().lowercase()
                filtered.clear()
                filtered.addAll(if (q.isEmpty()) allApps else allApps.filter { it.name.lowercase().contains(q) })
                adapter.notifyDataSetChanged()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        lv.setOnItemClickListener { _, _, pos, _ ->
            val app = filtered[pos]
            val result = Intent().apply {
                putExtra("app_name", app.name)
                putExtra("app_package", app.packageName)
            }
            setResult(Activity.RESULT_OK, result)
            finish()
        }
    }

    private fun loadApps(): List<AppInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
            .sortedBy { it.name.lowercase() }
    }
}

class AppPickerAdapter(private val ctx: android.content.Context, private val items: List<AppInfo>) :
    BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(pos: Int) = items[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false)
        val app = items[pos]
        row.findViewById<ImageView>(R.id.iv_app_icon).setImageDrawable(app.icon)
        row.findViewById<TextView>(R.id.tv_app_name).text = app.name
        row.findViewById<TextView>(R.id.tv_app_pkg).text = app.packageName
        return row
    }
}
