package com.ishaan.essentialvoice.glyph

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.Settings
import com.ishaan.essentialvoice.voice.Dictation
import com.nothing.thirdparty.IGlyphService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hardware Controller for Nothing Phone (3a / 2a Plus) Glyph LEDs.
 * Connects directly to Nothing OS system service `com.nothing.thirdparty.bind_glyphservice`
 * via `com.nothing.thirdparty.IGlyphService` AIDL.
 *
 * Phone (3a) Hardware Mapping:
 * - Channels 0..19 (20 LEDs): C-Arc Progress Bar & Meter
 * - Channels 20..30 (11 segments): Zone A (Top curved group for Voice & AI)
 * - Channels 31..35 (5 segments): Zone B (Bottom slash/status group)
 */
object NothingGlyphController {

    private const val TAG = "EVGlyph"
    private const val SERVICE_PACKAGE = "com.nothing.thirdparty"
    private const val SERVICE_ACTION = "com.nothing.thirdparty.bind_glyphservice"

    const val TOTAL_CHANNELS = 36
    const val C_PROGRESS_COUNT = 20 // Channels 0..19
    const val A_START = 20
    const val A_END = 31            // Channels 20..30 (11 LEDs)
    const val B_START = 31
    const val B_END = 36            // Channels 31..35 (5 LEDs)

    const val MAX_BRIGHTNESS = 4096
    const val DEFAULT_BRIGHTNESS = 3000
    const val DIM_BRIGHTNESS = 600
    const val MAX_GLYPH_DURATION_MS = 30_000L // Safety timeout

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var appContext: Context? = null
    private var glyphService: IGlyphService? = null
    private var isBound = false
    private var isSessionOpen = false

    private val frameBuffer = IntArray(TOTAL_CHANNELS) { 0 }

    private var breathingJob: Job? = null
    private var timeoutJob: Job? = null
    private var testJob: Job? = null

    private var currentSettings: Settings? = null
    private var isCurrentlyListening = false
    private var isKeyboardVisible = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                glyphService = IGlyphService.Stub.asInterface(service)
                isBound = true
                Log.d(TAG, "Connected to native Nothing Glyph Service")

                val pkg = appContext?.packageName ?: "com.ishaan.essentialvoice"
                glyphService?.register("test")
                glyphService?.registerSDK(pkg, "test")
                glyphService?.openSession()
                isSessionOpen = true

                turnOffAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Glyph session", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Disconnected from native Nothing Glyph Service")
            glyphService = null
            isBound = false
            isSessionOpen = false
        }
    }

    fun attach(context: Context) {
        appContext = context.applicationContext ?: context
        if (currentSettings == null) {
            try {
                currentSettings = Prefs.get(appContext!!).now
            } catch (e: Exception) {
                Log.w(TAG, "Could not load initial settings in attach", e)
            }
        }
        bindService()

        // Hook into Dictation activity and microphone levels
        Dictation.watch(
            key = "glyph",
            onActivity = { busy, listening ->
                onDictationActivity(busy, listening)
            },
            onLevel = { level ->
                onMicLevel(level)
            }
        )
    }

    fun detach() {
        Dictation.unwatch("glyph")
        unbindService()
        appContext = null
    }

    fun apply(settings: Settings) {
        currentSettings = settings
        if (!settings.glyphEnabled) {
            turnOffAll()
        }
    }

    fun maxBrightness(): Int {
        val s = currentSettings ?: appContext?.let { Prefs.get(it).now }
        return when (s?.glyphBrightness) {
            Prefs.GLYPH_BRIGHTNESS_MIN -> 450
            Prefs.GLYPH_BRIGHTNESS_MED -> 1600
            Prefs.GLYPH_BRIGHTNESS_MAX -> 4095
            else -> 4095
        }
    }

    fun defaultBrightness(): Int {
        val s = currentSettings ?: appContext?.let { Prefs.get(it).now }
        return when (s?.glyphBrightness) {
            Prefs.GLYPH_BRIGHTNESS_MIN -> 250
            Prefs.GLYPH_BRIGHTNESS_MED -> 1000
            Prefs.GLYPH_BRIGHTNESS_MAX -> 3200
            else -> 3200
        }
    }

    fun dimBrightness(): Int {
        val s = currentSettings ?: appContext?.let { Prefs.get(it).now }
        return when (s?.glyphBrightness) {
            Prefs.GLYPH_BRIGHTNESS_MIN -> 70
            Prefs.GLYPH_BRIGHTNESS_MED -> 250
            Prefs.GLYPH_BRIGHTNESS_MAX -> 800
            else -> 800
        }
    }

    /**
     * Dedicated Intensity Test: Lights up rear Glyph LEDs at the exact
     * selected intensity for 2.5 seconds so user can comfortably inspect it.
     */
    fun testIntensity(level: String? = null, onComplete: () -> Unit = {}) {
        testJob?.cancel()
        testJob = scope.launch {
            ensureSessionReady()
            frameBuffer.fill(0)
            flushBuffer()
            delay(50)

            val chosenLevel = level ?: currentSettings?.glyphBrightness
                ?: appContext?.let { Prefs.get(it).now.glyphBrightness }
                ?: Prefs.GLYPH_BRIGHTNESS_MAX

            val brightness = when (chosenLevel) {
                Prefs.GLYPH_BRIGHTNESS_MIN -> 450
                Prefs.GLYPH_BRIGHTNESS_MED -> 1600
                Prefs.GLYPH_BRIGHTNESS_MAX -> 4095
                else -> 4095
            }

            // Light up all channels (C-Arc, Zone A, Zone B) at the exact intensity
            for (i in 0 until TOTAL_CHANNELS) {
                frameBuffer[i] = brightness
            }
            flushBuffer()

            // Keep it lit for 2.5 seconds so user can flip the phone and inspect
            delay(2500)

            turnOffAll()
            onComplete()
        }
    }

    fun previewBrightness(level: String) {
        testIntensity(level)
    }


    private fun bindService() {
        val ctx = appContext ?: return
        if (isBound) return
        try {
            val intent = Intent(SERVICE_ACTION).apply {
                setPackage(SERVICE_PACKAGE)
            }
            ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind Nothing Glyph Service", e)
        }
    }

    private fun unbindService() {
        val ctx = appContext ?: return
        try {
            turnOffAll()
            if (isSessionOpen) {
                glyphService?.closeSession()
                isSessionOpen = false
            }
            if (isBound) {
                ctx.unbindService(serviceConnection)
                isBound = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding service", e)
        }
    }

    /**
     * Called whenever dictation state changes (listening or transcribing/busy).
     */
    fun onDictationActivity(busy: Boolean, listening: Boolean) {
        val s = currentSettings ?: return
        if (!s.glyphEnabled) return

        isCurrentlyListening = listening

        if (listening) {
            startSafetyTimeout()
            stopThinkingBreathing()
            if (s.glyphListening && !s.glyphVoiceReaction) {
                // Steady glow while listening if voice reaction is off
                for (i in A_START until A_END) frameBuffer[i] = defaultBrightness()
                flushBuffer()
            }
        } else if (busy) {
            // Transcribing / thinking phase
            if (s.glyphThinking) {
                startThinkingBreathing()
            } else {
                turnOffAll()
            }
        } else {
            // Completely done / idle
            stopThinkingBreathing()
            turnOffAll()
            if (isKeyboardVisible && s.glyphKeyboardOpen) {
                setKeyboardIndicator(true)
            }
        }
    }

    /**
     * Real-time microphone audio level reaction (Orb Talking style).
     * @param level Normalized float 0.0f .. 1.0f
     */
    fun onMicLevel(level: Float) {
        val s = currentSettings ?: return
        if (!s.glyphEnabled || !s.glyphVoiceReaction || !isCurrentlyListening) return

        ensureSessionReady()

        // Base resting glow plus dynamic audio boost
        val clampedLevel = level.coerceIn(0f, 1f)
        val maxB = maxBrightness()
        val dimB = dimBrightness()
        val voiceBrightness = (dimB + clampedLevel * (maxB - dimB)).toInt()

        synchronized(frameBuffer) {
            // Zone A (Top curved group) pulses with voice amplitude
            for (i in A_START until A_END) {
                frameBuffer[i] = voiceBrightness
            }

            // C-Arc LEDs (0..19) expand like a speech meter / talking orb
            val litCount = (clampedLevel * C_PROGRESS_COUNT).roundToInt().coerceIn(0, C_PROGRESS_COUNT)
            for (i in 0 until C_PROGRESS_COUNT) {
                frameBuffer[i] = if (i < litCount) voiceBrightness else 0
            }

            flushBuffer()
        }
    }

    /**
     * Confirmation double-blink when text is successfully delivered.
     */
    fun onDelivered() {
        val s = currentSettings ?: return
        if (!s.glyphEnabled || !s.glyphSuccessFlash) return

        scope.launch {
            ensureSessionReady()
            for (repeat in 0 until 2) {
                val maxB = maxBrightness()
                for (i in A_START until A_END) frameBuffer[i] = maxB
                flushBuffer()
                delay(100)
                for (i in A_START until A_END) frameBuffer[i] = 0
                flushBuffer()
                delay(70)
            }
            if (isKeyboardVisible && s.glyphKeyboardOpen) {
                setKeyboardIndicator(true)
            }
        }
    }

    /**
     * Keyboard open/closed indicator state.
     */
    fun onKeyboardStateChanged(open: Boolean) {
        isKeyboardVisible = open
        val s = currentSettings ?: return
        if (!s.glyphEnabled || !s.glyphKeyboardOpen || isCurrentlyListening) return

        setKeyboardIndicator(open)
    }

    private fun setKeyboardIndicator(on: Boolean) {
        ensureSessionReady()
        // Subtle indicator on Zone B center LED (channel 33)
        frameBuffer[33] = if (on) dimBrightness() else 0
        flushBuffer()
    }

    /**
     * Smooth breathing animation during transcribing/thinking.
     */
    private fun startThinkingBreathing() {
        breathingJob?.cancel()
        breathingJob = scope.launch {
            ensureSessionReady()
            var step = 0
            while (isActive) {
                val rad = Math.toRadians((step * 6).toDouble())
                val factor = ((sin(rad) + 1.0) / 2.0).toFloat()
                val defB = defaultBrightness()
                val dimB = dimBrightness()
                val brightness = (dimB + factor * (defB - dimB)).toInt()

                synchronized(frameBuffer) {
                    for (i in A_START until A_END) {
                        frameBuffer[i] = brightness
                    }
                    flushBuffer()
                }
                delay(33) // ~30 fps smooth pulse
                step = (step + 1) % 60
            }
        }
    }

    private fun stopThinkingBreathing() {
        breathingJob?.cancel()
        breathingJob = null
    }

    /**
     * Test sweep for settings preview.
     */
    fun runTestSweep(onComplete: () -> Unit = {}) {
        testJob?.cancel()
        testJob = scope.launch {
            ensureSessionReady()
            frameBuffer.fill(0)
            flushBuffer()
            delay(80)

            val defB = defaultBrightness()
            val maxB = maxBrightness()

            // Step 1: Sweep Small Arc (Zone B: 31..35)
            for (i in B_START until B_END) {
                frameBuffer[i] = defB
                flushBuffer()
                delay(50)
            }
            delay(100)

            // Step 2: Sweep C-Arc (0 to 19)
            for (i in 0 until C_PROGRESS_COUNT) {
                frameBuffer[i] = defB
                flushBuffer()
                delay(30)
            }
            delay(100)

            // Step 3: Strobe Zone A (20..30)
            for (i in A_START until A_END) frameBuffer[i] = maxB
            flushBuffer()
            delay(300)

            turnOffAll()
            onComplete()
        }
    }

    fun turnOffAll() {
        timeoutJob?.cancel()
        timeoutJob = null
        breathingJob?.cancel()
        breathingJob = null
        testJob?.cancel()
        testJob = null
        synchronized(frameBuffer) {
            frameBuffer.fill(0)
            flushBufferDirect()
        }
    }

    private fun startSafetyTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(MAX_GLYPH_DURATION_MS)
            turnOffAll()
        }
    }

    private fun ensureSessionReady() {
        if (!isBound) bindService()
        val svc = glyphService ?: return
        if (!isSessionOpen) {
            try {
                val pkg = appContext?.packageName ?: "com.ishaan.essentialvoice"
                svc.register("test")
                svc.registerSDK(pkg, "test")
                svc.openSession()
                isSessionOpen = true
            } catch (e: Exception) {
                Log.w(TAG, "Cannot open session yet", e)
            }
        }
    }

    private fun flushBuffer() {
        val svc = glyphService ?: return
        if (!isSessionOpen) return
        try {
            svc.setFrameColors(frameBuffer)
        } catch (e: Exception) {
            Log.w(TAG, "flushBuffer failed", e)
        }
    }

    private fun flushBufferDirect() {
        val svc = glyphService ?: return
        try {
            svc.setFrameColors(frameBuffer)
        } catch (e: Exception) {
            Log.w(TAG, "flushBufferDirect failed", e)
        }
    }
}
