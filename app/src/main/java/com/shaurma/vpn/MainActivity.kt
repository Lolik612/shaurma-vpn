package com.shaurma.vpn

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mainScreen: View
    private lateinit var keyScreen: View
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var shaurma: ImageView
    private lateinit var buttonHolder: View
    private lateinit var ringOn: View
    private lateinit var pulse1: View
    private lateinit var pulse2: View
    private lateinit var keyInput: EditText
    private lateinit var keyError: TextView
    private lateinit var keyCancel: TextView

    private lateinit var spin: ObjectAnimator
    private lateinit var breath: ObjectAnimator
    private lateinit var pulseAnim1: ObjectAnimator
    private lateinit var pulseAnim2: ObjectAnimator
    private var stopSpinAnim: ObjectAnimator? = null
    private var timerJob: Job? = null

    private val vpnState get() = VpnController.state.value

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showMain(animated = true)
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startVpn()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainScreen = findViewById(R.id.mainScreen)
        keyScreen = findViewById(R.id.keyScreen)
        statusText = findViewById(R.id.statusText)
        timerText = findViewById(R.id.timerText)
        shaurma = findViewById(R.id.shaurma)
        buttonHolder = findViewById(R.id.buttonHolder)
        ringOn = findViewById(R.id.ringOn)
        pulse1 = findViewById(R.id.pulse1)
        pulse2 = findViewById(R.id.pulse2)
        keyInput = findViewById(R.id.keyInput)
        keyError = findViewById(R.id.keyError)
        keyCancel = findViewById(R.id.keyCancel)

        setupAnimators()
        onBackPressedDispatcher.addCallback(this, backCallback)

        buttonHolder.setOnClickListener { onToggle() }
        findViewById<View>(R.id.locationCard).setOnClickListener {
            if (vpnState == VpnController.State.OFF) showKeyScreen(animated = true)
        }
        findViewById<View>(R.id.keyPaste).setOnClickListener { pasteFromClipboard() }
        findViewById<View>(R.id.keyContinue).setOnClickListener { submitKey() }
        keyCancel.setOnClickListener { showMain(animated = true) }

        if (savedKey() != null) showMain(animated = false) else showKeyScreen(animated = false)

        // Состояние VPN приходит от сервиса
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { VpnController.state.collect { applyState(it) } }
                launch {
                    VpnController.error.collect {
                        Toast.makeText(this@MainActivity, getString(R.string.error, it), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ───────────────────────── анимации ─────────────────────────

    private fun setupAnimators() {
        spin = ObjectAnimator.ofFloat(shaurma, View.ROTATION, 0f, 360f).apply {
            duration = 2400
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        breath = ObjectAnimator.ofPropertyValuesHolder(
            buttonHolder,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.045f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.045f)
        ).apply {
            duration = 1900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        pulseAnim1 = makePulse(pulse1, 0L)
        pulseAnim2 = makePulse(pulse2, 1300L)
        breath.start()
    }

    private fun makePulse(v: View, delay: Long) = ObjectAnimator.ofPropertyValuesHolder(
        v,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f),
        PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0f)
    ).apply {
        duration = 2600
        startDelay = delay
        repeatCount = ObjectAnimator.INFINITE
        interpolator = DecelerateInterpolator()
    }

    private fun setActive(active: Boolean) {
        ringOn.animate().alpha(if (active) 1f else 0f).setDuration(450).start()
        if (active) {
            breath.cancel()
            buttonHolder.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
            if (!pulseAnim1.isStarted) pulseAnim1.start()
            if (!pulseAnim2.isStarted) pulseAnim2.start()
        } else {
            pulseAnim1.cancel()
            pulseAnim2.cancel()
            for (p in listOf(pulse1, pulse2)) {
                p.alpha = 0f
                p.scaleX = 1f
                p.scaleY = 1f
            }
            if (!breath.isStarted) breath.start()
        }
    }

    private fun startSpin() {
        stopSpinAnim?.cancel()
        stopSpinAnim = null
        if (!spin.isStarted) spin.start()
    }

    /** Плавно доворачиваем шаурму до исходного положения, а не обрываем резко. */
    private fun stopSpin() {
        if (!spin.isStarted) return
        spin.cancel()
        val cur = ((shaurma.rotation % 360f) + 360f) % 360f
        val left = 360f - cur
        stopSpinAnim = ObjectAnimator.ofFloat(shaurma, View.ROTATION, cur, 360f).apply {
            duration = (250 + left / 360f * 900f).toLong()
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    shaurma.rotation = 0f
                }
            })
            start()
        }
    }

    private fun setStatus(textRes: Int, color: Int) {
        val newText = getString(textRes)
        if (statusText.text.toString() == newText) {
            statusText.setTextColor(color)
            return
        }
        statusText.animate().alpha(0f).setDuration(120).withEndAction {
            statusText.text = newText
            statusText.setTextColor(color)
            statusText.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    private fun applyState(s: VpnController.State) {
        when (s) {
            VpnController.State.OFF -> {
                setStatus(R.string.status_off, 0xFFBFA48E.toInt())
                setActive(false)
                stopSpin()
                stopTimer()
            }
            VpnController.State.CONNECTING -> {
                setStatus(R.string.status_connecting, 0xFFFFC477.toInt())
                setActive(true)
                startSpin()
            }
            VpnController.State.ON -> {
                setStatus(R.string.status_on, 0xFF9BE15D.toInt())
                setActive(true)
                startSpin()
                startTimer()
            }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        val since = VpnController.connectedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val sec = (System.currentTimeMillis() - since) / 1000
                timerText.text = String.format("%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        timerText.text = ""
    }

    // ───────────────────────── подключение ─────────────────────────

    private fun onToggle() {
        if (vpnState != VpnController.State.OFF) {
            startService(Intent(this, ShaurmaVpnService::class.java).setAction(ShaurmaVpnService.ACTION_DISCONNECT))
            return
        }
        if (savedKey() == null) {
            showKeyScreen(animated = true)
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startVpn()
    }

    private fun startVpn() {
        startService(Intent(this, ShaurmaVpnService::class.java).setAction(ShaurmaVpnService.ACTION_CONNECT))
    }

    // ───────────────────────── ключ доступа ─────────────────────────

    private fun prefs() = getSharedPreferences("vpn", MODE_PRIVATE)

    private fun savedKey(): String? =
        prefs().getString("key", null)?.takeIf { it.isNotBlank() }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.key_empty, Toast.LENGTH_SHORT).show()
            return
        }
        keyInput.setText(text)
        keyInput.setSelection(text.length)
        keyError.visibility = View.GONE
    }

    private fun submitKey() {
        try {
            val key = keyInput.text.toString().trim()
            KeyCodec.parse(key)
            prefs().edit().putString("key", key).apply()
            keyError.visibility = View.GONE
            keyInput.setText("")
            hideKeyboard()
            showMain(animated = true)
        } catch (e: Exception) {
            keyError.visibility = View.VISIBLE
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(keyInput.windowToken, 0)
    }

    // ───────────────────────── переключение экранов ─────────────────────────

    private fun showMain(animated: Boolean) {
        backCallback.isEnabled = false
        swap(show = mainScreen, hide = keyScreen, animated = animated)
    }

    private fun showKeyScreen(animated: Boolean) {
        val hasKey = savedKey() != null
        keyCancel.visibility = if (hasKey) View.VISIBLE else View.GONE
        backCallback.isEnabled = hasKey
        keyError.visibility = View.GONE
        swap(show = keyScreen, hide = mainScreen, animated = animated)
    }

    private fun swap(show: View, hide: View, animated: Boolean) {
        if (!animated) {
            hide.visibility = View.GONE
            show.visibility = View.VISIBLE
            show.alpha = 1f
            return
        }
        hide.animate().alpha(0f).setDuration(180).withEndAction {
            hide.visibility = View.GONE
            hide.alpha = 1f
            show.alpha = 0f
            show.visibility = View.VISIBLE
            show.animate().alpha(1f).setDuration(240).start()
        }.start()
    }
}
