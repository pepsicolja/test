package com.hexadecinull.vineos.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hexadecinull.vineos.BuildConfig
import com.hexadecinull.vineos.native.VineRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeviceDiagnosticsSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Проверка без root", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Проверяет доступ текущего контейнерного движка из самого приложения. " +
                "Root и Shizuku не нужны. Это не тест загрузки Android и не проверка полной эмуляции.",
            style = MaterialTheme.typography.bodySmall,
        )
        FilledTonalButton(
            enabled = !running,
            onClick = {
                running = true
                scope.launch {
                    try {
                        report = withContext(Dispatchers.IO) {
                            val result = VineRuntime.probeNamespaces()
                            "VineOS ${BuildConfig.VERSION_NAME}\n" +
                                "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                                "ABIs: ${Build.SUPPORTED_ABIS.joinToString()}\n" +
                                "Kernel: ${System.getProperty("os.version")}\n" +
                                "Context: app process, without privilege elevation\n" +
                                "Container probe: $result\n" +
                                "A failed probe blocks this container path only. " +
                                "A passed probe does not establish guest boot or access to all devices."
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        report = "Probe failed: ${error.message}"
                    } catch (error: LinkageError) {
                        report = "Native runtime unavailable: ${error.message}"
                    } finally {
                        running = false
                    }
                }
            },
        ) {
            Text(if (running) "Проверяется…" else "Проверить телефон")
        }
        report?.let { text ->
            Text(text, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(intent, "Отправить отчёт VineOS"))
            }) {
                Text("Поделиться отчётом")
            }
        }
    }
}
