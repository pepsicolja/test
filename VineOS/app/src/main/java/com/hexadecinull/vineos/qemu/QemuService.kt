package com.hexadecinull.vineos.qemu

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hexadecinull.vineos.VineApplication.Companion.CHANNEL_VM_RUNNING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class QemuService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            QemuEngine.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val open = PendingIntent.getActivity(this, 21, Intent(this, QemuActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 22, Intent(this, QemuService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_VM_RUNNING)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("VineOS: QEMU")
            .setContentText("Экспериментальная гостевая машина")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop)
            .setOngoing(true)
            .build()
        startForeground(1021, notification)
        job = scope.launch {
            try { QemuEngine.run(applicationContext, intent?.getBooleanExtra(DEMO, true) ?: true) }
            finally { stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        QemuEngine.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val DEMO = "demo"
        const val STOP = "com.hexadecinull.vineos.QEMU_STOP"
    }
}
