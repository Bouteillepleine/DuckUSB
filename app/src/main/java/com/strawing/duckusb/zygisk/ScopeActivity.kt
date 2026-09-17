package com.strawing.duckusb.zygisk

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.strawing.duckusb.common.Config
import com.strawing.duckusb.common.DuckConfig
import com.google.android.material.R as MR

class ScopeActivity : AppCompatActivity() {

    private data class Entry(val label: String, val pkg: String, val system: Boolean)

    private var config = DuckConfig()
    private val selected = LinkedHashSet<String>()
    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private lateinit var adapter: Adapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = Root.readConfig() ?: DuckConfig()
        selected.addAll(config.targets)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }

        container.addView(TextView(this).apply {
            text = if (config.frameworkAllApps) {
                "Every app is already covered by framework mode. Ticking an app here injects into it to spoof sys.usb.*, which a memory scan can see."
            } else {
                "Ticked apps read USB debugging as off."
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(attr(MR.attr.colorOnSurfaceVariant))
            setPadding(dp(4), 0, dp(4), dp(8))
        })

        val search = EditText(this).apply {
            hint = "Search apps"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
        }
        container.addView(search)

        val list = ListView(this).apply {
            divider = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
            ).apply { weight = 1f }
        }
        adapter = Adapter()
        list.adapter = adapter
        container.addView(list)

        setContentView(container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(12), bars.top + dp(8), dp(12), bars.bottom)
            insets
        }

        load()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    private fun load() {
        val pm = packageManager
        val entries = ArrayList<Entry>()
        for (info in pm.getInstalledApplications(0)) {
            if (info.packageName == packageName) continue
            if (info.packageName in Config.SPARE_PACKAGES) continue
            val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
            entries += Entry(
                label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                pkg = info.packageName,
                system = system,
            )
        }
        entries.sortWith(
            compareByDescending<Entry> { it.pkg in selected }
                .thenBy { it.label.lowercase() }
        )
        all = entries
        shown = entries
        adapter.notifyDataSetChanged()
    }

    private fun filter(query: String) {
        val q = query.trim().lowercase()
        shown = if (q.isEmpty()) all else all.filter {
            it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
        }
        adapter.notifyDataSetChanged()
    }

    private fun save() {
        config.targets = LinkedHashSet(selected)
        val wrote = Root.writeConfig(config) && Root.syncPackages(selected)
        if (!wrote) {
            Toast.makeText(this, "Could not write the module configuration", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val entry = shown[position]
            val row = (convertView as? LinearLayout) ?: buildRow()
            val icon = row.getChildAt(0) as ImageView
            val text = row.getChildAt(1) as LinearLayout
            val label = text.getChildAt(0) as TextView
            val pkg = text.getChildAt(1) as TextView
            val check = row.getChildAt(2) as CheckBox

            label.text = entry.label
            pkg.text = if (entry.system) "${entry.pkg} · system" else entry.pkg
            icon.setImageDrawable(
                runCatching { packageManager.getApplicationIcon(entry.pkg) }.getOrNull()
            )
            check.setOnCheckedChangeListener(null)
            check.isChecked = entry.pkg in selected
            check.setOnCheckedChangeListener { _, value ->
                if (value) selected.add(entry.pkg) else selected.remove(entry.pkg)
            }
            row.setOnClickListener { check.isChecked = !check.isChecked }
            return row
        }

        private fun buildRow(): LinearLayout {
            val row = LinearLayout(this@ScopeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
            }
            row.addView(ImageView(this@ScopeActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
            val text = LinearLayout(this@ScopeActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            text.addView(TextView(this@ScopeActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(attr(MR.attr.colorOnSurface))
            })
            text.addView(TextView(this@ScopeActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(attr(MR.attr.colorOnSurfaceVariant))
            })
            row.addView(text)
            row.addView(CheckBox(this@ScopeActivity))
            return row
        }
    }

    private fun attr(attrId: Int, fallback: Int = Color.GRAY): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attrId, value, true)) value.data else fallback
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
