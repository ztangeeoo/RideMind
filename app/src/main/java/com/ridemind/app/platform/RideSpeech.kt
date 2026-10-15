package com.ridemind.app.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class RideSpeech(private val context: Context) : TextToSpeech.OnInitListener {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var focusRequest: AudioFocusRequest? = null
    var onError: ((String) -> Unit)? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val languageResult = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = abandonFocus()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = abandonFocus()
                override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
            })
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                onError?.invoke("系统 TTS 不支持中文")
            }
        } else {
            onError?.invoke("系统 TTS 初始化失败")
        }
    }

    fun speak(text: String, utteranceId: String): Boolean {
        if (!ready || !requestDuckFocus()) return false
        val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) abandonFocus()
        return result == TextToSpeech.SUCCESS
    }

    fun stop() {
        tts?.stop()
        abandonFocus()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private fun requestDuckFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) tts?.stop() }
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) audioManager.abandonAudioFocus(null)
        focusRequest = null
    }
}
