package com.shaurma.vpn

import android.animation.ObjectAnimator
import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private class ShaurmaTunnel : Tunnel {
        override fun getName() = "shaurma"
        override fun onStateChange(newState: Tunnel.State) {}
    }

    private val tunnel = ShaurmaTunnel()
    private val backend by lazy { GoBackend(applicationContext) }

    private lateinit var statusText: TextView
    private lateinit var shaurma: ImageView
    private lateinit var buttonHolder: FrameLayout
    private lateinit var spin: ObjectAnimator

    private var connected = false
    private var busy = false

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) setTunnel(true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        shaurma = findViewById(R.id.shaurma)
        buttonHolder = findViewById(R.id.buttonHolder)

        spin = ObjectAnimator.ofFloat(shaurma, "rotation", 0f, 360f).apply {
            duration = 2200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        buttonHolder.setOnClickListener { onToggle() }
        findViewById<android.view.View>(R.id.locationCard).setOnClickListener { showConfigDialog() }

        // Синхронизируемся с реальным состоянием туннеля (если приложение перезапустили)
        lifecycleScope.launch {
            val up = withContext(Dispatchers.IO) {
                runCatching { backend.getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)
            }
            render(up)
        }
    }

    private fun onToggle() {
        if (busy) return
        if (connected) {
            setTunnel(false)
            return
        }
        if (loadConfigText() == null) {
            Toast.makeText(this, R.string.no_config, Toast.LENGTH_LONG).show()
            showConfigDialog()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else setTunnel(true)
    }

    private fun setTunnel(up: Boolean) {
        busy = true
        if (up) {
            statusText.setText(R.string.status_connecting)
            startSpin()
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (up) {
                        val text = loadConfigText() ?: error(getString(R.string.no_config))
                        val config = Config.parse(ByteArrayInputStream(text.toByteArray()))
                        backend.setState(tunnel, Tunnel.State.UP, config)
                    } else {
                        backend.setState(tunnel, Tunnel.State.DOWN, null)
                    }
                }
                render(up)
            } catch (e: Exception) {
                render(false)
                val msg = e.message ?: e.javaClass.simpleName
                Toast.makeText(this@MainActivity, getString(R.string.error, msg), Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    private fun render(up: Boolean) {
        connected = up
        if (up) {
            statusText.setText(R.string.status_on)
            statusText.setTextColor(0xFF9D92FF.toInt())
            buttonHolder.setBackgroundResource(R.drawable.ring_on)
            startSpin()
        } else {
            statusText.setText(R.string.status_off)
            statusText.setTextColor(0xFF8A8AA3.toInt())
            buttonHolder.setBackgroundResource(R.drawable.ring)
            stopSpin()
        }
    }

    private fun startSpin() {
        if (!spin.isStarted) spin.start()
    }

    private fun stopSpin() {
        spin.cancel()
        shaurma.rotation = 0f
    }

    // ---- конфиг ----

    private fun prefs() = getSharedPreferences("vpn", MODE_PRIVATE)

    private fun loadConfigText(): String? {
        prefs().getString("config", null)?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            assets.open("client.conf").bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun showConfigDialog() {
        if (connected) {
            Toast.makeText(this, R.string.status_on, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(loadConfigText().orEmpty())
            hint = getString(R.string.config_hint)
            minLines = 8
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.config_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                try {
                    Config.parse(ByteArrayInputStream(text.toByteArray()))
                    prefs().edit().putString("config", text).apply()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.bad_config, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
