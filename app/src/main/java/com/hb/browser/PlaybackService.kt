package com.hb.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class PlaybackService : Service() {

    companion object {
        @Volatile var instance: PlaybackService? = null
        const val CH = "hb_playback"
        const val ID = 11
        const val A_TOGGLE = "hb.toggle"
        const val A_NEXT = "hb.next"
        const val A_PREV = "hb.prev"
    }

    private lateinit var session: MediaSession
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        session = MediaSession(this, "HB_Browser").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = WebHolder.setPlaying(true)
                override fun onPause() = WebHolder.setPlaying(false)
                override fun onSkipToNext() { WebHolder.next() }
                override fun onSkipToPrevious() { WebHolder.prev() }
            })
            isActive = true
        }
        updateSession()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            A_TOGGLE -> WebHolder.setPlaying(!WebHolder.playing)
            A_NEXT -> WebHolder.next()
            A_PREV -> WebHolder.prev()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(ID, n)
    }

    fun refresh() {
        updateSession()
        getSystemService(NotificationManager::class.java).notify(ID, build())
        updateLocks()
    }

    private fun updateSession() {
        val playing = WebHolder.playing
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, WebHolder.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "HB_Browser")
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                ).build()
        )
    }

    private fun build(): Notification {
        val playing = WebHolder.playing
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun act(icon: Int, label: String, a: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), label,
            PendingIntent.getService(this, a.hashCode(), Intent(this, PlaybackService::class.java).setAction(a), flags)
        ).build()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags
        )
        val close = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Close",
            PendingIntent.getActivity(
                this, 99, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_EXIT, true), flags
            )
        ).build()

        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_hb)
            .setContentTitle(WebHolder.title)
            .setContentText(if (playing) "Playing" else "Paused")
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(act(android.R.drawable.ic_media_previous, "Previous", A_PREV))
            .addAction(
                act(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play", A_TOGGLE
                )
            )
            .addAction(act(android.R.drawable.ic_media_next, "Next", A_NEXT))
            .addAction(close)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun updateLocks() {
        if (WebHolder.playing) {
            if (wake == null) {
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HB:play")
                    .apply { acquire(6 * 60 * 60 * 1000L) }
            }
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HB:wifi").apply { acquire() }
            }
        } else releaseLocks()
    }

    private fun releaseLocks() {
        wake?.let { if (it.isHeld) it.release() }; wake = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
    }

    override fun onDestroy() {
        releaseLocks()
        session.release()
        instance = null
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
