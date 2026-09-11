package uk.krodity.pcremote.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * The process-wide loopback stream server.
 *
 * A singleton rather than a ViewModel field because [MediaStreamService] has to
 * reach the same instance, and because the server outliving a configuration
 * change is exactly what we want mid-playback.
 */
object MediaStreams {

    @Volatile private var instance: MediaCacheServer? = null

    fun server(
        cacheDir: File,
        fetch: (String, Long, Long) -> ByteArray,
    ): MediaCacheServer = instance ?: synchronized(this) {
        instance ?: MediaCacheServer(cacheDir, fetch).also { instance = it }
    }

    fun current(): MediaCacheServer? = instance

    fun shutdown() {
        instance?.stop()
        instance = null
    }
}

/**
 * Keeps the app's process alive and unfrozen while a player is streaming.
 *
 * Without this the stream dies partway through a film. The loopback server runs
 * inside this app's process, so the moment Android decides the app is a
 * backgrounded nobody -- and this phone is unusually eager about that; Moto's
 * `moto_freezer` froze it 35 seconds in during testing -- the socket stops
 * being serviced and playback stalls with no useful error.
 *
 * The type is `dataSync` rather than `mediaPlayback`: this process transfers
 * file data, it does not own playback or a media session. Claiming a type we do
 * not implement is what gets a foreground service killed.
 *
 * It stops itself once nothing has read for a minute, so closing the player
 * does not leave a notification and a live socket behind.
 */
class MediaStreamService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var idleCheck: Runnable

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        idleCheck = object : Runnable {
            override fun run() {
                val server = MediaStreams.current()
                if (server == null || server.isIdle()) {
                    Log.i(TAG, "no readers for a minute; stopping")
                    stopSelf()
                } else {
                    handler.postDelayed(this, CHECK_MS)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "media"
        startForeground(NOTIFICATION_ID, notification(title))
        handler.removeCallbacks(idleCheck)
        handler.postDelayed(idleCheck, CHECK_MS)
        // Restarting with no intent would leave us streaming nothing.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(idleCheck)
        super.onDestroy()
    }

    private fun notification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Streaming from the PC")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Media streaming", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while a file is streaming to a player" }
        )
    }

    companion object {
        private const val TAG = "MediaStreamService"
        private const val CHANNEL = "media-stream"
        private const val NOTIFICATION_ID = 4711
        private const val CHECK_MS = 20_000L
        private const val EXTRA_TITLE = "title"

        fun start(ctx: Context, title: String) {
            val i = Intent(ctx, MediaStreamService::class.java)
                .putExtra(EXTRA_TITLE, title)
            ContextCompatStartForeground(ctx, i)
        }

        @Suppress("FunctionName")
        private fun ContextCompatStartForeground(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
