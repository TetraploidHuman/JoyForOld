package com.tetraploid.joyforold.uitreetest

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), UiTreeTestAccessibilityService.TreeUpdateListener {

    private lateinit var statusView: TextView
    private lateinit var treeView: TextView
    private lateinit var refreshButton: Button
    private lateinit var autoRefreshButton: Button

    private val treeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showTree(UiTreeTestAccessibilityService.latestDump)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusText)
        treeView = findViewById(R.id.treeText)
        refreshButton = findViewById(R.id.refreshButton)
        autoRefreshButton = findViewById(R.id.autoRefreshButton)

        treeView.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        refreshButton.setOnClickListener {
            showTree(UiTreeTestAccessibilityService.refreshNow())
        }
        autoRefreshButton.setOnClickListener {
            UiTreeTestAccessibilityService.autoRefreshEnabled =
                !UiTreeTestAccessibilityService.autoRefreshEnabled
            updateAutoRefreshLabel()
        }
        findViewById<Button>(R.id.copyButton).setOnClickListener {
            val text = treeView.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("ui-tree", text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.logcatButton).setOnClickListener {
            val text = treeView.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            FullUiTreeDumper.logToLogcat(text)
            Toast.makeText(this, R.string.logged, Toast.LENGTH_SHORT).show()
        }

        updateConnectionStatus()
        updateAutoRefreshLabel()
        showTree(UiTreeTestAccessibilityService.latestDump)
    }

    override fun onStart() {
        super.onStart()
        UiTreeTestAccessibilityService.addListener(this)
        ContextCompat.registerReceiver(
            this,
            treeUpdateReceiver,
            IntentFilter(UiTreeTestAccessibilityService.ACTION_TREE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        updateConnectionStatus()
        if (UiTreeTestAccessibilityService.isConnected()) {
            showTree(UiTreeTestAccessibilityService.refreshNow())
        }
    }

    override fun onStop() {
        UiTreeTestAccessibilityService.removeListener(this)
        unregisterReceiver(treeUpdateReceiver)
        super.onStop()
    }

    override fun onTreeUpdated(dump: String) {
        runOnUiThread { showTree(dump) }
    }

    private fun showTree(dump: String) {
        updateConnectionStatus()
        if (dump.isBlank() && !UiTreeTestAccessibilityService.isConnected()) {
            treeView.text = getString(R.string.service_not_connected)
            return
        }
        treeView.text = dump
    }

    private fun updateConnectionStatus() {
        val connected = UiTreeTestAccessibilityService.isConnected()
        statusView.text = if (connected) {
            "无障碍服务：已连接"
        } else {
            "无障碍服务：未连接"
        }
        refreshButton.isEnabled = connected
        autoRefreshButton.isEnabled = connected
    }

    private fun updateAutoRefreshLabel() {
        autoRefreshButton.text = getString(
            if (UiTreeTestAccessibilityService.autoRefreshEnabled) {
                R.string.auto_refresh_on
            } else {
                R.string.auto_refresh_off
            },
        )
    }
}
