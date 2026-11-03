package com.hexadecinull.vineos.qemu.arm

import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hexadecinull.vineos.ui.theme.VineOSTheme

class ArmVmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { VineOSTheme { VmScreen() } }
    }

    @Composable
    private fun VmScreen() {
        val state by ArmEngine.state.collectAsState()
        LaunchedEffect(Unit) { ArmEngine.initialize(applicationContext) }
        val apk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) ArmEngine.install(applicationContext, uri)
        }
        BackHandler(enabled = state.running && state.booted) { ArmEngine.key(4) }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Android 5.1 · ARM32", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { finish() }) { Text("К VineOS") }
                }
                Text("Старые APK · без root · данные гостя сохраняются", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.prepared) {
                        Button(onClick = { ArmEngine.prepare(applicationContext) }, enabled = !state.busy && !state.running) {
                            Text("Подготовить Android")
                        }
                        if (state.busy && !state.running) TextButton(onClick = { ArmEngine.cancelPreparation() }) { Text("Отмена") }
                    } else {
                        Button(onClick = { ArmEngine.start(applicationContext) }, enabled = !state.running && !state.busy && state.surface) { Text("Запустить") }
                        OutlinedButton(onClick = { ArmEngine.stop() }, enabled = state.running) { Text("Стоп") }
                    }
                }
                if (!state.prepared && !state.busy) Text("Один раз скачивается архив 186 МБ. Первый запуск Android займёт несколько минут.", style = MaterialTheme.typography.bodySmall)
                Text(state.status, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
                    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val aspect = ArmCommand.WIDTH.toFloat() / ArmCommand.HEIGHT
                        val width = minOf(maxWidth, maxHeight * aspect)
                        AndroidView(
                            modifier = Modifier.width(width).aspectRatio(aspect).clipToBounds(),
                            factory = { context ->
                                TextureView(context).apply {
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        private var display: Surface? = null
                                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                                            texture.setDefaultBufferSize(ArmCommand.WIDTH, ArmCommand.HEIGHT)
                                            display = Surface(texture).also(ArmEngine::attach)
                                        }
                                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {}
                                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                            val old = display
                                            display = null
                                            if (old != null) ArmEngine.detach(old) { old.release(); texture.release() }
                                            else texture.release()
                                            return false
                                        }
                                    }
                                    setOnTouchListener { view, event ->
                                        when (event.actionMasked) {
                                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                                val down = event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE
                                                ArmEngine.touch(event.x / view.width.coerceAtLeast(1), event.y / view.height.coerceAtLeast(1), down)
                                                if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                                                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                                            }
                                        }
                                        true
                                    }
                                }
                            },
                        )
                    }
                    if (!state.running) Text("Экран гостя", color = Color.LightGray)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { ArmEngine.key(4) }, enabled = state.booted && !state.busy) { Text("Назад") }
                    OutlinedButton(onClick = { ArmEngine.key(3) }, enabled = state.booted && !state.busy) { Text("Домой") }
                    Button(
                        onClick = { apk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                        enabled = state.booted && !state.busy,
                    ) { Text("Установить APK") }
                }
                TextButton(onClick = {
                    val report = "VineOS Android 5 ARM32\n${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}, ABI ${Build.SUPPORTED_ABIS.joinToString()}\n${state.status}\n${state.log}"
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Отчёт VineOS"))
                }) { Text("Поделиться отчётом") }
            }
        }
    }
}
