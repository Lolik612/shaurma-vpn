package com.shaurma.vpn

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var mainScreen: View
    private lateinit var loginScreen: View
    private lateinit var settingsScreen: View
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var subText: TextView
    private lateinit var shaurma: ImageView
    private lateinit var buttonHolder: View
    private lateinit var ringOn: View
    private lateinit var pulse1: View
    private lateinit var pulse2: View
    private lateinit var btnTelegram: TextView
    private lateinit var loginCode: TextView
    private var loginLink: String? = null
    private lateinit var loginError: TextView
    private lateinit var loginSub: TextView

    private lateinit var spin: ObjectAnimator
    private lateinit var breath: ObjectAnimator
    private lateinit var pulseAnim1: ObjectAnimator
    private lateinit var pulseAnim2: ObjectAnimator
    private var stopSpinAnim: ObjectAnimator? = null
    private var timerJob: Job? = null

    private var loginJob: Job? = null
    private var fetching = false

    private val vpnState get() = VpnController.state.value

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showScreen(mainScreen)
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) startVpn()
            else {
                applyState(vpnState)
                Toast.makeText(this, "Нужно разрешить VPN-подключение", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainScreen = findViewById(R.id.mainScreen)
        loginScreen = findViewById(R.id.loginScreen)
        settingsScreen = findViewById(R.id.settingsScreen)
        statusText = findViewById(R.id.statusText)
        timerText = findViewById(R.id.timerText)
        subText = findViewById(R.id.subText)
        shaurma = findViewById(R.id.shaurma)
        buttonHolder = findViewById(R.id.buttonHolder)
        ringOn = findViewById(R.id.ringOn)
        pulse1 = findViewById(R.id.pulse1)
        pulse2 = findViewById(R.id.pulse2)
        btnTelegram = findViewById(R.id.btnTelegram)
        loginCode = findViewById(R.id.loginCode)
        loginError = findViewById(R.id.loginError)
        loginSub = findViewById(R.id.loginSub)

        setupAnimators()
        onBackPressedDispatcher.addCallback(this, backCallback)

        buttonHolder.setOnClickListener { onToggle() }
        findViewById<View>(R.id.gear).setOnClickListener { openSettings() }
        findViewById<View>(R.id.subCard).setOnClickListener { openSettings() }
        btnTelegram.setOnClickListener {
            val link = loginLink
            if (link != null) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    .onFailure { showLoginError("Не удалось открыть Telegram. Отправь код боту вручную") }
            } else doTelegram()
        }
        loginCode.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("code", loginCode.text.toString().replace(" ", "")))
            Toast.makeText(this, "Код скопирован", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.setBuy).setOnClickListener { openBot() }
        findViewById<View>(R.id.setDiag).setOnClickListener { showDiag() }
        findViewById<View>(R.id.setLogout).setOnClickListener { logout() }
        findViewById<View>(R.id.setBack).setOnClickListener { showScreen(mainScreen) }

        if (loggedIn()) {
            showScreen(mainScreen, animated = false)
            updateSubUi()
        } else {
            prepareLogin()
            showScreen(loginScreen, animated = false)
        }

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

    override fun onResume() {
        super.onResume()
        if (loggedIn()) refreshStatus()
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
        if (fetching) return
        fetching = true
        setStatus(R.string.status_connecting, 0xFFFFC477.toInt())
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Api.post("/api/config", creds()) }
                saveSub(r)
                when {
                    r.optBoolean("ok") -> {
                        prefs().edit().putString("key", r.getString("key")).apply()
                        val intent = VpnService.prepare(this@MainActivity)
                        if (intent != null) vpnPermission.launch(intent) else startVpn()
                    }
                    r.optString("error") == "no_subscription" -> {
                        applyState(vpnState)
                        showNoSub()
                    }
                    r.optString("error") == "bad_token" -> logout()
                    else -> {
                        applyState(vpnState)
                        Toast.makeText(this@MainActivity, "Ошибка сервера: ${r.optString("error")}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                applyState(vpnState)
                Toast.makeText(this@MainActivity, "Нет связи с сервером (${e.javaClass.simpleName}). Проверь интернет", Toast.LENGTH_LONG).show()
            } finally {
                fetching = false
            }
        }
    }

    private fun startVpn() {
        startService(Intent(this, ShaurmaVpnService::class.java).setAction(ShaurmaVpnService.ACTION_CONNECT))
    }

    private fun showNoSub() {
        AlertDialog.Builder(this)
            .setTitle("Подписка не активна")
            .setMessage("Возьми бесплатные 12 часов или купи подписку в нашем Telegram-боте. Не забудь указать свой юзер: ${prefs().getString("user", "")}")
            .setPositiveButton("Открыть бота") { _, _ -> openBot() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun openBot() {
        val bot = prefs().getString("bot", "") ?: ""
        if (bot.isEmpty()) {
            Toast.makeText(this, "Бот пока недоступен", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$bot"))) }
    }

    // ───────────────────────── аккаунт ─────────────────────────

    private fun prefs() = getSharedPreferences("vpn", MODE_PRIVATE)

    private fun loggedIn() = !prefs().getString("token", null).isNullOrEmpty() && !prefs().getString("user", null).isNullOrEmpty()

    private fun creds() = JSONObject()
        .put("username", prefs().getString("user", ""))
        .put("token", prefs().getString("token", ""))

    private fun saveSub(r: JSONObject) {
        val e = prefs().edit()
        r.optString("bot").takeIf { it.isNotEmpty() }?.let { e.putString("bot", it) }
        if (r.has("active")) {
            e.putBoolean("active", r.optBoolean("active"))
                .putBoolean("forever", r.optBoolean("forever"))
                .putLong("expires", r.optLong("expires"))
        }
        e.apply()
        updateSubUi()
    }

    private fun subSummary(): String {
        val p = prefs()
        return when {
            p.getBoolean("forever", false) -> "Бессрочная ♾"
            p.getBoolean("active", false) ->
                "до " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(p.getLong("expires", 0) * 1000))
            p.contains("active") -> "Не активна"
            else -> "Проверяем…"
        }
    }

    private fun updateSubUi() {
        subText.text = subSummary()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Api.post("/api/status", creds()) }
                if (r.optString("error") == "bad_token") logout() else saveSub(r)
            } catch (_: Exception) {
                if (!prefs().contains("active")) subText.text = "Нет связи с сервером"
            }
        }
    }

    private fun logout() {
        startService(Intent(this, ShaurmaVpnService::class.java).setAction(ShaurmaVpnService.ACTION_DISCONNECT))
        prefs().edit().clear().apply()
        loginJob?.cancel()
        prepareLogin()
        showScreen(loginScreen)
    }

    private fun prepareLogin() {
        loginError.visibility = View.GONE
        btnTelegram.isEnabled = true
        btnTelegram.text = "Войти через Telegram"
        loginLink = null
        loginCode.visibility = View.GONE
        loginSub.text = "Получи код и отправь его нашему боту — аккаунт привяжется, подписка и покупки будут на нём"
        if (BuildConfig.API_URL.isBlank()) showLoginError("Сервер не настроен в этой сборке")
    }

    private fun showLoginError(msg: String) {
        loginError.text = msg
        loginError.visibility = View.VISIBLE
    }

    private fun doTelegram() {
        loginError.visibility = View.GONE
        loginJob?.cancel()
        btnTelegram.isEnabled = false
        btnTelegram.text = "Подключаюсь…"
        loginJob = lifecycleScope.launch {
            try {
                val st = withContext(Dispatchers.IO) { Api.post("/api/tg_start", JSONObject()) }
                if (!st.optBoolean("ok")) {
                    showLoginError(loginMessage(st.optString("error")) + "\nАдрес: ${BuildConfig.API_URL}")
                    prepareLogin()
                    return@launch
                }
                val code = st.getString("code")
                loginLink = st.optString("link").ifEmpty { null }
                loginCode.text = code.chunked(4).joinToString(" ")
                loginCode.visibility = View.VISIBLE
                btnTelegram.isEnabled = true
                btnTelegram.text = "Открыть бота"
                loginSub.text = "Отправь этот код боту @${st.optString("bot")} (кнопка «Войти в приложение») или просто нажми «Открыть бота». Приложение войдёт само"
                repeat(200) {
                    delay(1500)
                    val r = withContext(Dispatchers.IO) {
                        runCatching { Api.post("/api/tg_poll", JSONObject().put("code", code)) }.getOrNull()
                    } ?: return@repeat
                    when {
                        r.optBoolean("ok") && !r.optBoolean("pending") -> {
                            finishLogin(r)
                            return@launch
                        }
                        !r.optBoolean("ok") -> {
                            showLoginError("Ссылка устарела, нажми кнопку ещё раз")
                            prepareLogin()
                            return@launch
                        }
                    }
                }
                showLoginError("Не дождались подтверждения, попробуй ещё раз")
                prepareLogin()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showLoginError("Нет связи с сервером: ${e.javaClass.simpleName} ${e.message ?: ""}".trim() + "\nАдрес: ${BuildConfig.API_URL}")
                prepareLogin()
            }
        }
    }

    private fun loginMessage(code: String) = when (code) {
        "no_server" -> "Сервер не настроен в этой сборке"
        "rate_limit" -> "Слишком много попыток, подожди минуту"
        else -> "Ошибка сервера: $code"
    }

    private fun finishLogin(r: JSONObject) {
        prefs().edit()
            .putString("user", r.getString("username"))
            .putString("token", r.getString("token"))
            .putString("bot", r.optString("bot"))
            .apply()
        updateSubUi()
        showScreen(mainScreen)
        refreshStatus()
    }

    // ───────────────────────── настройки и диагностика ─────────────────────────

    private fun openSettings() {
        findViewById<TextView>(R.id.setUser).text = prefs().getString("user", "")
        findViewById<TextView>(R.id.setSub).text = subSummary()
        showScreen(settingsScreen)
    }

    private fun showDiag() {
        val sb = StringBuilder("Состояние: $vpnState\nВерсия: ${BuildConfig.VERSION_NAME}\n")
        VpnController.statsFn?.let { sb.append(it()).append('\n') }
        sb.append("\n").append(VpnController.dump().ifEmpty { "(журнал пуст — сначала подключись)" })
        val hev = runCatching { File(filesDir, "hev.log").readLines().takeLast(25) }.getOrDefault(emptyList())
        if (hev.isNotEmpty()) sb.append("\n\n— туннель —\n").append(hev.joinToString("\n"))
        val text = sb.toString()
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Диагностика")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Копировать") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("diag", text))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    // ───────────────────────── переключение экранов ─────────────────────────

    private fun showScreen(target: View, animated: Boolean = true) {
        backCallback.isEnabled = target === settingsScreen
        for (v in listOf(mainScreen, loginScreen, settingsScreen)) {
            if (v === target) continue
            if (v.visibility == View.VISIBLE && animated) {
                v.animate().alpha(0f).setDuration(160).withEndAction { v.visibility = View.GONE; v.alpha = 1f }.start()
            } else {
                v.visibility = View.GONE
            }
        }
        target.alpha = if (animated) 0f else 1f
        target.visibility = View.VISIBLE
        if (animated) target.animate().alpha(1f).setStartDelay(120).setDuration(220).start()
    }
}
