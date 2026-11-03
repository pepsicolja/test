package com.hexadecinull.vineos.qemu.arm

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

class ArmVmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { ArmEngine.stop(); stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY
        val open = PendingIntent.getActivity(this, 31, Intent(this, ArmVmActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 32, Intent(this, ArmVmService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_VM_RUNNING)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("VineOS · Android 5 ARM32")
            .setContentText("Гостевая система работает")
            .setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Остановить", stop).build()
        startForeground(1031, notification)
        job = scope.launch { try { ArmEngine.run(applicationContext) } finally { stopSelf() } }
        return START_NOT_STICKY
    }
    override fun onDestroy() { ArmEngine.stop(); scope.cancel(); super.onDestroy() }
    companion object { const val STOP = "com.hexadecinull.vineos.ARM_STOP" }
}
