package com.marco.photosphere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Elabora i progetti in background (servizio in primo piano con notifica di avanzamento):
 * puoi chiudere l'app o usare il telefono, la foto viene unita comunque e ti avvisa alla fine.
 */
class StitchService : Service() {

    private class Job(val id: String, val mode: Int)

    private val queue = LinkedBlockingQueue<Job>()
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private lateinit var nm: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(progressNotification("Photo Sphere", "Preparo…", 0f, false))
        val id = intent?.getStringExtra("id")
        val mode = intent?.getIntExtra("mode", Pipeline.FULL) ?: Pipeline.FULL
        if (id != null) {
            Jobs.set(id, 0f, "In coda", true)
            queue.add(Job(id, mode))
        }
        if (worker == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhotoSphere:stitch").apply {
                setReferenceCounted(false)
                acquire(45 * 60 * 1000L)
            }
            worker = Thread { loop() }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, n)
        }
    }

    private fun loop() {
        while (true) {
            val job = queue.poll(2, TimeUnit.SECONDS) ?: break
            runJob(job)
        }
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        worker = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun runJob(job: Job) {
        val p = Projects.get(this, job.id)
        if (p == null) { Jobs.clear(job.id); return }
        var last = -1
        try {
            Pipeline.run(this, p, job.mode) { v, msg ->
                Jobs.set(job.id, v, msg)
                val pct = (v * 100).toInt()
                if (pct != last) {
                    last = pct
                    nm.notify(NOTIF_PROGRESS, progressNotification(p.name, msg, v, true))
                }
            }
            notifyDone(p)
        } catch (oom: OutOfMemoryError) {
            fail(p, "Memoria insufficiente: riprova con 4K")
        } catch (e: Exception) {
            fail(p, e.message ?: "errore")
        } finally {
            Jobs.clear(job.id)
        }
    }

    private fun fail(p: Project, msg: String) {
        try {
            p.lastError = msg
            p.save()
        } catch (_: Exception) {
        }
        val open = PendingIntent.getActivity(
            this, p.id.hashCode(), Intent(this, EditorActivity::class.java).putExtra("id", p.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Unione non riuscita")
            .setContentText(msg)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_DONE + (p.id.hashCode() and 0xFFFF), n)
    }

    private fun notifyDone(p: Project) {
        val uri = p.resultUri?.let { Uri.parse(it) }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val view = if (uri != null) PendingIntent.getActivity(
            this, p.id.hashCode(), Intent(this, ViewerActivity::class.java).setData(uri), flags
        ) else null
        val edit = PendingIntent.getActivity(
            this, p.id.hashCode() + 1, Intent(this, EditorActivity::class.java).putExtra("id", p.id), flags
        )
        val pct = (p.coverage * 100).toInt()
        val text = if (p.coverage in 0.01f..0.97f) "Copertura $pct%: le zone non scattate restano scure. Tocca per aprirla." else "Tocca per aprirla."
        val b = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("La tua foto 360° è pronta")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .addAction(0, "Modifica", edit)
        if (view != null) b.setContentIntent(view) else b.setContentIntent(edit)
        nm.notify(NOTIF_DONE + (p.id.hashCode() and 0xFFFF), b.build())
    }

    private fun progressNotification(title: String, msg: String, v: Float, determinate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ProjectsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Unisco la foto 360°")
            .setContentText("$title · $msg")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (v * 100).toInt(), !determinate)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val CH_PROGRESS = "elaborazione"
        const val CH_DONE = "completato"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_DONE = 2000

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_PROGRESS, "Elaborazione in corso", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Avanzamento dell'unione delle foto"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Foto pronte", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Avviso quando la foto 360° è pronta"
                }
            )
        }

        fun enqueue(ctx: Context, id: String, mode: Int) {
            Jobs.set(id, 0f, "In coda", true)
            val i = Intent(ctx, StitchService::class.java).putExtra("id", id).putExtra("mode", mode)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }
    }
}
