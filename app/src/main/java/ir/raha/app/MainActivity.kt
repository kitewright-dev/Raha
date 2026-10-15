package ir.raha.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ir.raha.app.ui.OrbButton
import ir.raha.app.vpn.ConnectionState
import ir.raha.app.vpn.RahaState
import ir.raha.app.vpn.RahaVpnService
import ir.raha.nativebridge.rahacore.Rahacore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    private lateinit var orb: OrbButton
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var statsCard: View
    private lateinit var timerValue: TextView
    private lateinit var downValue: TextView
    private lateinit var downRate: TextView
    private lateinit var upValue: TextView
    private lateinit var upRate: TextView
    private lateinit var exitFlag: TextView
    private lateinit var exitName: TextView
    private lateinit var exitMeta: TextView

    private val glow = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(withAlpha(IDLE_GLOW, 0x40), Color.TRANSPARENT)
    }
    private var glowColor = IDLE_GLOW
    private var glowAnim: ValueAnimator? = null
    private var statsJob: Job? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) RahaVpnService.start(this)
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(buildContent())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RahaState.state.collect(::render)
            }
        }
    }

    override fun onStop() {
        statsJob?.cancel()
        statsJob = null
        super.onStop()
    }

    private fun onOrbTapped() {
        when (RahaState.state.value) {
            is ConnectionState.Idle, is ConnectionState.Failed -> {
                val intent = VpnService.prepare(this)
                if (intent != null) vpnPermission.launch(intent) else RahaVpnService.start(this)
            }
            is ConnectionState.Stopping -> Unit
            else -> RahaVpnService.stop(this)
        }
    }

    // ---- state -> UI ------------------------------------------------------

    private fun render(state: ConnectionState) {
        when (state) {
            ConnectionState.Idle -> {
                show(OrbButton.Mode.IDLE, IDLE_GLOW, "Tap to connect", "Raha finds a working relay for you")
            }
            ConnectionState.Discovering ->
                show(OrbButton.Mode.BUSY, BUSY_GLOW, "Finding relays…", "Fetching the signed relay list")
            is ConnectionState.Testing ->
                show(OrbButton.Mode.BUSY, BUSY_GLOW, "Testing relays…", "Secure handshake on ${state.candidates} candidates")
            is ConnectionState.Connected -> {
                show(OrbButton.Mode.ON, ON_GLOW, "Connected", "via ${state.relayName}")
                bindExit(state)
            }
            ConnectionState.Stopping ->
                show(OrbButton.Mode.BUSY, BUSY_GLOW, "Disconnecting…", "")
            is ConnectionState.Failed ->
                show(OrbButton.Mode.FAIL, FAIL_GLOW, "Couldn't connect", state.reason)
        }
        setStatsVisible(state is ConnectionState.Connected)
        if (state is ConnectionState.Connected) startStats(state.sinceElapsed) else stopStats()
    }

    private fun show(mode: OrbButton.Mode, glowTarget: Int, title: String, sub: String) {
        orb.setMode(mode)
        orb.contentDescription = if (mode == OrbButton.Mode.ON) "Disconnect" else "Connect"
        status.text = title
        detail.text = sub
        detail.visibility = if (sub.isEmpty()) View.INVISIBLE else View.VISIBLE
        animateGlow(glowTarget)
    }

    private fun bindExit(state: ConnectionState.Connected) {
        val code = state.countryCode?.trim()?.takeIf { it.length == 2 }?.uppercase(Locale.ROOT)
        exitFlag.text = code?.let(::flagEmoji) ?: "🌐"
        exitName.text = code?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH) }
            ?: state.countryCode ?: "Unknown"
        exitMeta.text = listOfNotNull(state.city, "${state.latencyMs} ms").joinToString("  ·  ")
    }

    private fun startStats(since: Long) {
        if (statsJob?.isActive == true) return
        statsJob = lifecycleScope.launch {
            var lastUp = runCatching { Rahacore.uplinkBytes() }.getOrDefault(0L)
            var lastDown = runCatching { Rahacore.downlinkBytes() }.getOrDefault(0L)
            var lastAt = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                timerValue.text = formatDuration((now - since) / 1000)
                val up = runCatching { Rahacore.uplinkBytes() }.getOrDefault(lastUp)
                val down = runCatching { Rahacore.downlinkBytes() }.getOrDefault(lastDown)
                val secs = ((now - lastAt).coerceAtLeast(1L)) / 1000.0
                upValue.text = formatBytes(up)
                downValue.text = formatBytes(down)
                upRate.text = "↑ ${formatBytes(((up - lastUp).coerceAtLeast(0L) / secs).toLong())}/s"
                downRate.text = "↓ ${formatBytes(((down - lastDown).coerceAtLeast(0L) / secs).toLong())}/s"
                lastUp = up; lastDown = down; lastAt = now
                delay(1000)
            }
        }
    }

    private fun stopStats() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun setStatsVisible(visible: Boolean) {
        val targetAlpha = if (visible) 1f else 0f
        if (statsCard.alpha == targetAlpha && (statsCard.visibility == View.VISIBLE) == visible) return
        statsCard.animate().cancel()
        if (visible) {
            statsCard.visibility = View.VISIBLE
            statsCard.translationY = px(10).toFloat()
            statsCard.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(EASE_OUT).start()
        } else {
            statsCard.animate().alpha(0f).setDuration(150).setInterpolator(EASE_OUT)
                .withEndAction { statsCard.visibility = View.GONE }.start()
        }
    }

    private fun animateGlow(target: Int) {
        if (target == glowColor) return
        val from = glowColor
        glowAnim?.cancel()
        glowAnim = ValueAnimator.ofObject(ArgbEvaluator(), from, target).apply {
            duration = 320
            interpolator = EASE_OUT
            addUpdateListener {
                glowColor = it.animatedValue as Int
                glow.colors = intArrayOf(withAlpha(glowColor, 0x40), Color.TRANSPARENT)
            }
            start()
        }
    }

    // ---- layout -----------------------------------------------------------

    private fun buildContent(): View {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#0A1222"), BG, Color.parseColor("#04060B"))
        )
        val frame = FrameLayout(this).apply { background = LayerDrawable(arrayOf<Drawable>(base, glow)) }
        frame.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            glow.gradientRadius = v.width * 0.95f
            glow.setGradientCenter(0.5f, 0.36f)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(40), px(20), px(48))
        }
        // Android 15 draws apps edge to edge: keep content clear of the status
        // and navigation bars while the background glow runs behind them.
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(px(20) + bars.left, px(14) + bars.top, px(20) + bars.right, px(24) + bars.bottom)
            insets
        }

        root.addView(header())

        orb = OrbButton(this).apply { setOnClickListener { onOrbTapped() } }
        root.addView(orb, LinearLayout.LayoutParams(px(264), px(264)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = px(22)
        })

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        detail = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, px(6), 0, 0)
            maxLines = 3
        }
        root.addView(detail, LinearLayout.LayoutParams(-1, -2))

        statsCard = statsCard().apply { alpha = 0f; visibility = View.GONE }
        root.addView(statsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22) })

        root.addView(supportCard(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root, ViewGroup.LayoutParams(-1, -2))
        }
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return frame
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.raha_logo)
            contentDescription = null
        }, LinearLayout.LayoutParams(px(40), px(40)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Raha"
                setTextColor(Color.WHITE)
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Verified relays · REALITY"
                setTextColor(MUTED)
                textSize = 12f
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(MUTED)
            textSize = 11f
            setPadding(px(10), px(4), px(10), px(4))
            background = pill(Color.parseColor("#14FFFFFF"))
        })
    }

    private fun statsCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = card()
        setPadding(px(18), px(16), px(18), px(16))

        // Exit row
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            exitFlag = TextView(this@MainActivity).apply { textSize = 26f }
            addView(exitFlag)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), 0, 0, 0)
                addView(label("EXIT LOCATION"))
                exitName = TextView(this@MainActivity).apply {
                    setTextColor(Color.WHITE); textSize = 16f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                addView(exitName)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            exitMeta = TextView(this@MainActivity).apply { setTextColor(MUTED); textSize = 12f }
            addView(exitMeta)
        })

        addView(View(this@MainActivity).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(-1, px(1)).apply { topMargin = px(14); bottomMargin = px(14) })

        // Metrics row
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            timerValue = metricValue()
            downValue = metricValue()
            upValue = metricValue()
            downRate = metricSub(ON_TEXT)
            upRate = metricSub(UP_TEXT)
            addView(metric("TIME", timerValue, metricSub(MUTED).apply { text = "connected" }), LinearLayout.LayoutParams(0, -2, 1f))
            addView(metric("DOWNLOAD", downValue, downRate), LinearLayout.LayoutParams(0, -2, 1f))
            addView(metric("UPLOAD", upValue, upRate), LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun supportCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = card()
        setPadding(px(16), px(14), px(16), px(14))
        isClickable = true
        isFocusable = true
        contentDescription = "Open support channel on Telegram"
        setOnClickListener {
            val tg = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$SUPPORT_HANDLE"))
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$SUPPORT_HANDLE"))
            runCatching { startActivity(tg) }.onFailure { runCatching { startActivity(web) } }
        }
        setOnTouchListener(pressFeedback())

        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_telegram)
            contentDescription = null
        }, LinearLayout.LayoutParams(px(40), px(40)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Support channel"
                setTextColor(Color.WHITE); textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                text = "@$SUPPORT_HANDLE · news, help and updates"
                setTextColor(MUTED); textSize = 12f
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "›"
            textSize = 24f
            setTextColor(MUTED)
        })
    }

    private fun metric(title: String, value: TextView, sub: TextView): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title))
            addView(value)
            addView(sub)
        }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(FAINT)
        textSize = 10.5f
        letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun metricValue() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        setPadding(0, px(4), 0, px(2))
        text = "—"
    }

    private fun metricSub(color: Int) = TextView(this).apply {
        setTextColor(color)
        textSize = 11.5f
        fontFeatureSettings = "tnum"
    }

    private fun card() = GradientDrawable().apply {
        cornerRadius = px(22).toFloat()
        colors = intArrayOf(Color.parseColor("#E6111A2B"), Color.parseColor("#E60B111D"))
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        setStroke(px(1), Color.parseColor("#1FFFFFFF"))
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = px(99).toFloat()
        setColor(color)
    }

    private fun pressFeedback() = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(120).setInterpolator(EASE_OUT).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(EASE_OUT).start()
        }
        false
    }

    // ---- helpers ----------------------------------------------------------

    private fun flagEmoji(code: String): String {
        val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private companion object {
        const val SUPPORT_HANDLE = "parsv2r"
        val BG = Color.parseColor("#05080F")
        val MUTED = Color.parseColor("#8E9AB0")
        val FAINT = Color.parseColor("#5C6880")
        val DIVIDER = Color.parseColor("#14FFFFFF")
        val ON_TEXT = Color.parseColor("#3FE0A8")
        val UP_TEXT = Color.parseColor("#5AB4FF")
        val IDLE_GLOW = Color.parseColor("#2E7DFF")
        val BUSY_GLOW = Color.parseColor("#6A5CFF")
        val ON_GLOW = Color.parseColor("#20E3A0")
        val FAIL_GLOW = Color.parseColor("#FF5A6A")
        val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    }
}
