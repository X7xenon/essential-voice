package com.ishaan.essentialvoice.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.ishaan.essentialvoice.Prefs

/**
 * Manages audio focus while the user is dictating/speaking.
 *
 * Depending on [com.ishaan.essentialvoice.Settings.mediaDucking], this requests audio focus from
 * Android's [AudioManager] so that background media playback (Spotify, YouTube, podcast players, etc.)
 * is either ducked (volume lowered) or paused while the microphone is open.
 *
 * When dictation ends, focus is abandoned so the background media restores its normal volume
 * or automatically resumes playback.
 */
object MediaFocusController {

    private const val TAG = "EVMediaFocus"

    @Volatile
    private var isHoldingFocus = false

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Request audio focus according to [mode] ("duck", "pause", or "off").
     * Safe to call multiple times; no-op if already holding focus or if mode is "off".
     */
    @Synchronized
    fun acquire(context: Context, mode: String) {
        if (mode == Prefs.MEDIA_OFF) return
        if (isHoldingFocus) return

        val am = audioManager ?: ((context.applicationContext ?: context).getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?.also { audioManager = it }
            ?: return

        val focusGain = when (mode) {
            Prefs.MEDIA_PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            Prefs.MEDIA_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val req = AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener({ change ->
                        Log.d(TAG, "Audio focus changed: $change")
                    }, mainHandler)
                    .build()

                val result = am.requestAudioFocus(req)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    focusRequest = req
                    isHoldingFocus = true
                    Log.i(TAG, "Audio focus acquired ($mode)")
                } else {
                    Log.w(TAG, "Audio focus request refused ($result)")
                }
            } else {
                @Suppress("DEPRECATION")
                val result = am.requestAudioFocus(
                    { },
                    AudioManager.STREAM_MUSIC,
                    focusGain,
                )
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    isHoldingFocus = true
                    Log.i(TAG, "Audio focus acquired legacy ($mode)")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to acquire audio focus", t)
        }
    }

    /**
     * Abandon audio focus and restore previous media playback.
     */
    @Synchronized
    fun release() {
        if (!isHoldingFocus) return
        val am = audioManager ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { req ->
                    am.abandonAudioFocusRequest(req)
                }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus { }
            }
            Log.i(TAG, "Audio focus released, media restored")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release audio focus", t)
        } finally {
            isHoldingFocus = false
        }
    }
}
