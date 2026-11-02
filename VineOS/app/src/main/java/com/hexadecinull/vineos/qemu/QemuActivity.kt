package com.hexadecinull.vineos.qemu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.hexadecinull.vineos.ui.theme.VineOSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QemuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VineOSTheme { QemuScreen() } }
    }

    @Composable
    private fun QemuScreen() {
        val state by QemuEngine.state.collectAsState()
        val scope = rememberCoroutineScope()
        var viewer by remember { mutableStateOf<NativeGuestView?>(null) }
        var download by remember { mutableStateOf<Job?>(null) }
        LaunchedEffect(Unit) { QemuEngine.refresh(applicationContext) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch {
                withContext(Dispatchers.IO) { QemuEngine.importIso(applicationContext, uri) }
            }
        }
        fun launchGuest(demo: Boolean) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, QemuService::class.java).putExtra(QemuService.DEMO, demo),
            )
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Text("VineOS · QEMU", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Запуск без root. Первый тест — встроенная гостевая машина. " +
                        "Далее можно выбрать Live ISO Android-x86 5.1. ARM-приложения и сохранение данных гостя пока не поддерживаются.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { launchGuest(true) }, enabled = !state.running && !state.importing) { Text("Пробный запуск") }
                    OutlinedButton(onClick = { QemuEngine.stop() }, enabled = state.running) { Text("Стоп") }
                }
                if (!state.imageReady) {
                    TextButton(onClick = {
                        download = scope.launch { withContext(Dispatchers.IO) { QemuEngine.downloadAndroid(applicationContext) } }
                    }, enabled = !state.running && !state.importing) { Text("Скачать Android 5.1 · 358 МБ") }
                }
                if (download?.isActive == true) {
                    TextButton(onClick = { download?.cancel() }) { Text("Отменить скачивание") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.running && !state.importing) { Text("Выбрать ISO") }
                    Button(onClick = { launchGuest(false) }, enabled = state.imageReady && !state.running && !state.importing) { Text("Запустить ISO") }
                }
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                if (state.running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((label, key) in listOf("Enter" to 0xff0d, "Esc" to 0xff1b, "↑" to 0xff52, "↓" to 0xff54, "⌫" to 0xff08)) {
                            OutlinedButton(onClick = { viewer?.key(key) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text(label) }
                        }
                    }
                    OutlinedTextField(
                        value = "",
                        onValueChange = { value ->
                            value.codePoints().forEach { code -> viewer?.key(if (code <= 255) code else 0x01000000 + code) }
                        },
                        label = { Text("Ввод текста") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(state.display, style = MaterialTheme.typography.bodySmall)
                    AndroidView(
                        factory = { context -> NativeGuestView(context, QemuEngine::displayStatus).also { viewer = it } },
                        onRelease = { view -> if (viewer === view) viewer = null; view.close() },
                        modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                    )
                } else {
                    Text(
                        "В пробном запуске должен появиться экран BIOS, затем надпись VINEOS QEMU TEST OK. " +
                            "Это проверка выполнения гостевого x86-кода, а не загруженный Android.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(top = 12.dp),
                    )
                }
                if (state.log.isNotBlank()) {
                    Text(state.log, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()))
                }
                TextButton(onClick = {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "VineOS QEMU preview\n${state.message}\n${state.display}\n${state.log}")
                    }, "Отчёт QEMU"))
                }) { Text("Поделиться отчётом") }
            }
        }
    }
}
