package com.vishnu.remindme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.vishnu.remindme.R
import com.vishnu.remindme.alarm.AlarmUtils
import com.vishnu.remindme.db.ReminderDAO
import com.vishnu.remindme.model.Reminder
import com.vishnu.remindme.settings.SettingsRepository
import com.vishnu.remindme.ui.AlarmActivity
import com.vishnu.remindme.utils.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Owns a ringing alarm for its whole lifetime: sound, vibration, and the ongoing alarm
 * notification (with a full-screen intent so [AlarmActivity] appears over the lock screen).
 *
 * The alarm keeps ringing until the user dismisses it — from [AlarmActivity] or from the
 * notification's Dismiss action — both of which send [ACTION_DISMISS] here. Dismissing a
 * one-time reminder deletes it from the database.
 */
@AndroidEntryPoint
class AlarmForegroundService : LifecycleService() {

    @Inject
    lateinit var reminderDAO: ReminderDAO

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "com.vishnu.remindme.alarm_service_channel_v2"
        const val NOTIFICATION_ID = 1

        /** Stops the ringing alarm and cleans up the fired reminder. */
        const val ACTION_DISMISS = "com.vishnu.remindme.action.DISMISS_ALARM"

        /** Pre-v2 channel id; deleted on upgrade since it carried a default sound. */
        private const val LEGACY_CHANNEL_ID = "com.vishnu.remindme.alarm_service_channel"

        private val _ringingReminderId = MutableStateFlow<Long?>(null)

        /**
         * Id of the reminder currently ringing, or `null` when nothing rings. [AlarmActivity]
         * observes this to close itself when the alarm is dismissed elsewhere (notification).
         */
        val ringingReminderId: StateFlow<Long?> = _ringingReminderId.asStateFlow()

        fun dismissIntent(context: Context): Intent =
            Intent(context, AlarmForegroundService::class.java).setAction(ACTION_DISMISS)
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_DISMISS) {
            dismiss()
            return START_NOT_STICKY
        }

        val reminder = intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                it.getParcelableExtra(Constants.REMINDER_ITEM_KEY, Reminder::class.java)
            else
                @Suppress("DEPRECATION")
                it.getParcelableExtra<Reminder>(Constants.REMINDER_ITEM_KEY)
        }

        if (reminder == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must happen promptly after startForegroundService().
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(reminder),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else 0
        )

        // A second alarm firing while one rings replaces it; the earlier one-time reminder is
        // left in the list (it was never dismissed).
        stopSound()
        _ringingReminderId.value = reminder._id
        startSound()

        if (reminder.recurrencePattern != null) {
            AlarmUtils.rescheduleAlarm(this, reminder)?.let { next ->
                lifecycleScope.launch(Dispatchers.IO) { reminderDAO.update(next) }
            }
        }

        // Unlocked phone: the full-screen intent only shows a heads-up notification, so keep
        // launching the ringing screen directly (allowed while we hold an overlay window).
        if (Settings.canDrawOverlays(this)) {
            showOverlay(this) {
                startActivity(alarmActivityIntent(reminder))
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSound()
        _ringingReminderId.value = null
        super.onDestroy()
    }

    private fun dismiss() {
        val id = _ringingReminderId.value
        stopSound()
        _ringingReminderId.value = null

        lifecycleScope.launch {
            if (id != null) withContext(Dispatchers.IO) { deleteIfFiredOneTime(id) }
            ServiceCompat.stopForeground(this@AlarmForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Deletes the reminder only if it is (still) a one-time reminder whose time has passed —
     * re-reading it from the DB so an edit made while ringing (new time, now recurring) wins.
     */
    private suspend fun deleteIfFiredOneTime(id: Long) {
        val current = reminderDAO.getById(id) ?: return
        if (current.recurrencePattern == null && current.dueDate <= System.currentTimeMillis())
            reminderDAO.deleteById(id)
    }

    private fun alarmActivityIntent(reminder: Reminder) =
        Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra(Constants.REMINDER_ITEM_KEY, reminder)
        }

    // region sound + vibration

    private fun startSound() {
        val settings = SettingsRepository(applicationContext)

        // A null URI means the user picked "Silent" — play no sound at all.
        settings.resolveRingtoneUri()?.let { uri ->
            // getRingtone() returns null if the stored URI is no longer playable
            // (e.g. a custom sound that was since deleted); fall back to the default
            // alarm sound, and skip sound entirely if even that is unavailable.
            val resolved = RingtoneManager.getRingtone(applicationContext, uri)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?.let { RingtoneManager.getRingtone(applicationContext, it) }
            resolved?.apply {
                audioAttributes = alarmAudioAttributes()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
            ringtone = resolved
        }

        if (settings.vibrateEnabled) startVibration()
    }

    private fun startVibration() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (!vib.hasVibrator()) return
        vibrator = vib

        // Repeating wait/buzz/wait pattern, looping from index 0.
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)

        // Tag the vibration as an alarm so it isn't suppressed by the device's
        // ringer mode (a plain vibrate() is treated like a notification buzz).
        @Suppress("DEPRECATION")
        vib.vibrate(effect, alarmAudioAttributes())
    }

    private fun stopSound() {
        ringtone?.takeIf { it.isPlaying }?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    // endregion

    /**
     * Displays a transparent overlay using the WindowManager.
     * Calls [onOverlayShown] once the overlay is displayed.
     */
    private fun showOverlay(context: Context, onOverlayShown: () -> Unit) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayView = View(context).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayView, params)

        onOverlayShown()

        Handler(Looper.getMainLooper()).postDelayed({
            windowManager.removeView(overlayView)
        }, 500)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)

        // Drop the legacy channel: it played a default sound, which conflicts with the
        // user-chosen ringtone (and with "Silent"). This service plays sound + vibration
        // itself, so the channel stays silent.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(reminder: Reminder): Notification {
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openAlarm = PendingIntent.getActivity(
            this, reminder._id.toInt(), alarmActivityIntent(reminder), immutable
        )
        val dismiss = PendingIntent.getService(this, 0, dismissIntent(this), immutable)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(reminder.title)
            .setSubText(reminder.description)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAlarm)
            .setFullScreenIntent(openAlarm, true)
            // Android 14+ lets users swipe away FGS notifications; treat that as Dismiss so
            // the alarm can't keep ringing without any visible way to stop it.
            .setDeleteIntent(dismiss)
            .addAction(0, getString(R.string.dismiss), dismiss)
            .build()
    }
}
