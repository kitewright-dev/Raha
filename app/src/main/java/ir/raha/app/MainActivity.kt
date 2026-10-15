package ir.raha.app

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import ir.raha.app.vpn.ConnectionState
import ir.raha.app.vpn.RahaState
import ir.raha.app.vpn.RahaVpnService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var orb: FrameLayout
    private lateinit var orbLabel: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private val orbBackground = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var orbColor = IDLE
    private var colorAnimator: ValueAnimator? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) RahaVpnService.start(this)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
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

    private fun render(state: ConnectionState) {
        when (state) {
            ConnectionState.Idle -> show(IDLE, "Connect", "Not connected", "Tap to find a working relay")
            ConnectionState.Discovering -> show(BUSY, "Cancel", "Finding relays…", "Fetching the signed relay directory")
            is ConnectionState.Testing -> show(BUSY, "Cancel", "Testing relays…", "Handshake test on ${state.candidates} candidates")
            is ConnectionState.Connected -> show(
                ON, "Disconnect", "Connected",
                listOfNotNull(state.relayName, state.country, "${state.latencyMs} ms").joinToString("  ·  ")
            )
            ConnectionState.Stopping -> show(BUSY, "…", "Stopping…", "")
            is ConnectionState.Failed -> show(FAIL, "Retry", "Couldn't connect", state.reason)
        }
    }

    private fun show(color: Int, label: String, title: String, sub: String) {
        orbLabel.text = label
        status.text = title
        detail.text = sub
        animateOrbColor(color)
    }

    private fun animateOrbColor(target: Int) {
        if (target == orbColor) return
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), orbColor, target).apply {
            duration = 240
            interpolator = EASE_OUT
            addUpdateListener {
                orbColor = it.animatedValue as Int
                orbBackground.setColor(orbColor)
            }
            start()
        }
    }

    private fun buildContent(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BG)
            setPadding(px(24), px(56), px(24), px(32))
        }

        root.addView(TextView(this).apply {
            text = "Raha"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.02f
        })
        root.addView(TextView(this).apply {
            text = "Verified relays · direct REALITY"
            setTextColor(MUTED)
            textSize = 13f
            setPadding(0, px(4), 0, 0)
        })

        val spacerTop = View(this)
        root.addView(spacerTop, LinearLayout.LayoutParams(1, 0, 1f))

        orbBackground.setColor(orbColor)
        orb = FrameLayout(this).apply {
            background = orbBackground
            isClickable = true
            isFocusable = true
            contentDescription = "Connect"
            setOnClickListener { onOrbTapped() }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(120).setInterpolator(EASE_OUT).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(EASE_OUT).start()
                }
                false
            }
        }
        orbLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        orb.addView(orbLabel, FrameLayout.LayoutParams(-1, -1))
        root.addView(orb, LinearLayout.LayoutParams(px(208), px(208)))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, px(32), 0, 0)
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

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(FAINT)
            textSize = 12f
        })
        return root
    }

    private companion object {
        val BG = Color.parseColor("#0E1116")
        val MUTED = Color.parseColor("#8B95A5")
        val FAINT = Color.parseColor("#4A5260")
        val IDLE = Color.parseColor("#252B35")
        val BUSY = Color.parseColor("#3A4A63")
        val ON = Color.parseColor("#1F8A5B")
        val FAIL = Color.parseColor("#8A3A3A")
        val EASE_OUT = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    }
}
