package com.example.tvweb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Start screen: type a web address or pick one of your saved sites. */
class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var savedList: LinearLayout
    private lateinit var savedTitle: TextView
    private val prefs by lazy { getSharedPreferences("sites", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.url_input)
        savedList = findViewById(R.id.saved_list)
        savedTitle = findViewById(R.id.saved_title)

        findViewById<Button>(R.id.open_button).setOnClickListener { open(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                open(urlInput.text.toString()); true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        renderSavedSites()
    }

    private fun normalize(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
    }

    private fun open(raw: String) {
        val url = normalize(raw)
        if (url == null) {
            Toast.makeText(this, "Type a website address first, like example.com", Toast.LENGTH_SHORT).show()
            return
        }
        saveSite(url)
        startActivity(Intent(this, BrowserActivity::class.java).putExtra(BrowserActivity.EXTRA_URL, url))
    }

    // ---- saved sites (most recent first, max 12) ----

    private fun loadSites(): MutableList<String> =
        (prefs.getString("list", "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()

    private fun storeSites(sites: List<String>) =
        prefs.edit().putString("list", sites.take(12).joinToString("\n")).apply()

    private fun saveSite(url: String) {
        val sites = loadSites()
        sites.remove(url)
        sites.add(0, url)
        storeSites(sites)
    }

    private fun removeSite(url: String) {
        val sites = loadSites()
        sites.remove(url)
        storeSites(sites)
    }

    private fun renderSavedSites() {
        savedList.removeAllViews()
        val sites = loadSites()
        savedTitle.visibility = if (sites.isEmpty()) View.GONE else View.VISIBLE

        val pad = (20 * resources.displayMetrics.density).toInt()
        for (url in sites) {
            val button = Button(this).apply {
                text = url.removePrefix("https://").removePrefix("http://").trimEnd('/')
                isAllCaps = false
                textSize = 20f
                setTextColor(getColor(R.color.text))
                setBackgroundResource(R.drawable.tv_button)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(pad, pad / 2, pad, pad / 2)
                setOnClickListener { open(url) }
                setOnLongClickListener {
                    removeSite(url)
                    renderSavedSites()
                    Toast.makeText(this@MainActivity, "Removed $text", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 }
            savedList.addView(button, params)
        }
    }
}
