package com.ollfx.spotifypool

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(18, 22, 29))
        }

        val title = TextView(this).apply {
            text = "SPOTIFY POOL"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        content.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Free aiming-assistant development build"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 22)
        }
        content.addView(subtitle, matchWrap())

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(190, 220, 190))
            text = "Status: app launched. Screen analysis is not implemented yet."
            setPadding(0, 12, 0, 18)
        }
        content.addView(status, matchWrap())

        content.addView(actionButton("Check overlay permission") {
            val allowed = Settings.canDrawOverlays(this)
            status.text = if (allowed) {
                "Overlay permission: granted"
            } else {
                "Overlay permission: not granted. Tap the next button to open Android settings."
            }
        }, matchWrap())

        content.addView(actionButton("Open overlay settings") {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                status.text = "Overlay permission is already granted."
            }
        }, matchWrap())

        content.addView(actionButton("Run local diagnostics") {
            status.text = buildString {
                appendLine("Diagnostics")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine("Package: $packageName")
                appendLine("Overlay permission: ${Settings.canDrawOverlays(this@MainActivity)}")
                appendLine("Screen capture: not started")
                appendLine("Table / ball detection: not implemented")
                appendLine("Trajectory engine: not implemented")
            }
        }, matchWrap())

        val note = TextView(this).apply {
            text = "\nBuild status: foundation scaffold. This APK does not yet detect balls or draw shot predictions. Those features need implementation and testing against real game screenshots."
            textSize = 13f
            setTextColor(Color.LTGRAY)
        }
        content.addView(note, matchWrap())

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 12 }
}
