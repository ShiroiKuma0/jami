/*
 * 白い熊 fork (sk): an incoming call while a call in another app is up stays quiet.
 *
 * 白い熊, 2026-11-27: in a WhatsApp call an incoming call rang into the ear — with the audio in
 * communication mode a ringtone comes out of the earpiece. So while a call of another app is up
 * (WhatsApp, 臨電話, the phone's own, …) an incoming Jami call does not ring, does not raise the
 * full-screen call screen and does not take the audio route: it vibrates once and shows a small
 * card at the top of the display (QuietCallCard). Messages are posted silently meanwhile.
 *
 * Detection is the audio mode alone. Telecom is not asked, because it would count the very call
 * that is coming in. The verdict is taken once per call, at its first sight, and kept until the
 * call is over — the mode changes under us as soon as the call is answered.
 */
package cx.ring.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object QuietCall {
    private const val TAG = "QuietCall"

    /** Our own calls that are not over: while one exists, the audio mode may be our own doing. */
    private val ownCalls: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val verdicts = ConcurrentHashMap<String, Boolean>()

    fun isCallMode(mode: Int): Boolean =
        mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_CALL_SCREENING

    /** A call is up on this phone — any app's, our own included. */
    fun callUp(context: Context): Boolean = try {
        isCallMode((context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode)
    } catch (e: Exception) {
        false
    }

    /** Whether the incoming call [callId] is to stay quiet. Decided at the first question, then kept. */
    fun decide(context: Context, callId: String): Boolean = verdicts.computeIfAbsent(callId) {
        val quiet = ownCalls.none { it != callId } && callUp(context)
        Log.w(TAG, "incoming call $callId: ${if (quiet) "another app's call is up — staying quiet" else "ringing as usual"}")
        quiet
    }

    /** The verdict already taken for [callId]; false when none was. */
    fun isQuiet(callId: String): Boolean = verdicts[callId] == true

    /** [callId] is a call of ours that takes the audio (anything but a quiet ringing one). */
    fun own(callId: String) {
        ownCalls.add(callId)
    }

    fun ended(callId: String) {
        ownCalls.remove(callId)
    }

    fun forget(callId: String) {
        verdicts.remove(callId)
        ownCalls.remove(callId)
    }

    /**
     * The one alert of a quiet call: two short pulses. Given as a ringtone vibration so that the
     * system lets it through from the background; none when the phone is on silent.
     */
    fun vibrateOnce(context: Context) {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 220, 140, 220), -1)
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                vibrator.vibrate(effect, android.os.VibrationAttributes.Builder(attributes).build())
            else
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attributes)
        } catch (e: Exception) {
            Log.w(TAG, "Can't vibrate for the quiet call", e)
        }
    }
}
