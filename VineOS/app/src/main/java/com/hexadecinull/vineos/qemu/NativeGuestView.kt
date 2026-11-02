package com.hexadecinull.vineos.qemu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class NativeGuestView(context: Context, private val status: (String) -> Unit) : View(context) {
    private sealed interface Input {
        data class Key(val value: Int) : Input
        data class Pointer(val x: Int, val y: Int, val down: Boolean) : Input
    }
    private var connection: RfbClient? = null
    private var scope: CoroutineScope? = null
    private var inputs: Channel<Input>? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private var pressed = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (connection != null) return
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = worker
        val events = Channel<Input>(Channel.UNLIMITED)
        inputs = events
        lateinit var client: RfbClient
        client = RfbClient(onFrame = { width, height, pixels ->
            val frame = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            post {
                if (connection === client && isAttachedToWindow) {
                    bitmap = frame
                    status("Изображение получено: $width × $height")
                    invalidate()
                }
            }
        })
        connection = client
        status("Подключение к экрану…")
        worker.launch {
            try { client.run() }
            catch (error: Exception) {
                post { if (connection === client) status("Экран: ${error.message}") }
            } finally { client.close() }
        }
        worker.launch {
            try {
                for (event in events) when (event) {
                    is Input.Key -> client.key(event.value)
                    is Input.Pointer -> client.pointer(event.x, event.y, event.down)
                }
            } catch (_: Exception) { client.close() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val frame = bitmap ?: return
        val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val w = frame.width * scale
        val h = frame.height * scale
        bounds.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(frame, null, bounds, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val frame = bitmap ?: return true
        if (bounds.width() <= 0 || bounds.height() <= 0) return true
        val x = ((event.x - bounds.left) * frame.width / bounds.width()).toInt().coerceIn(0, frame.width - 1)
        val y = ((event.y - bounds.top) * frame.height / bounds.height()).toInt().coerceIn(0, frame.height - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = true; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressed = false; performClick() }
            MotionEvent.ACTION_MOVE -> Unit
            else -> return true
        }
        inputs?.trySend(Input.Pointer(x, y, pressed))
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    fun key(value: Int) { inputs?.trySend(Input.Key(value)) }

    fun close() {
        connection?.close()
        connection = null
        inputs?.cancel()
        scope?.cancel()
        scope = null
        bitmap = null
        pressed = false
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }
}
