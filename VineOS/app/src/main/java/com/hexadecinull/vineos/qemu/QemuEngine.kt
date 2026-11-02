package com.hexadecinull.vineos.qemu

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import java.net.URL
import java.net.HttpURLConnection
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class QemuState(
    val running: Boolean = false,
    val importing: Boolean = false,
    val imageReady: Boolean = false,
    val message: String = "Готов к пробному запуску",
    val log: String = "",
)

internal object QemuEngine {
    private val mutable = MutableStateFlow(QemuState())
    val state = mutable.asStateFlow()
    private val stopped = AtomicBoolean(false)
    @Volatile private var process: Process? = null

    private fun directory(context: Context) = File(context.filesDir, "qemu").also { it.mkdirs() }
    private fun iso(context: Context) = File(directory(context), "guest.iso")

    fun refresh(context: Context) {
        mutable.update { it.copy(imageReady = iso(context).isFile) }
    }

    fun importIso(context: Context, uri: Uri) {
        if (state.value.running || state.value.importing) return
        mutable.update { it.copy(importing = true, message = "Копирование ISO…") }
        val temp = File(directory(context), "guest.iso.part")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: error("Не удалось открыть выбранный файл")
            val marker = ByteArray(5)
            RandomAccessFile(temp, "r").use {
                require(it.length() > 32774) { "Файл слишком мал для ISO" }
                it.seek(32769)
                it.readFully(marker)
            }
            require(marker.contentEquals("CD001".toByteArray(Charsets.US_ASCII))) {
                "Нужен ISO Android-x86. ARM-образы, APK и .vrom не подходят."
            }
            Files.move(temp.toPath(), iso(context).toPath(), StandardCopyOption.REPLACE_EXISTING)
            mutable.update { it.copy(imageReady = true, message = "ISO импортирован") }
        } catch (error: Exception) {
            mutable.update { it.copy(message = error.message ?: "Ошибка импорта") }
        } finally {
            temp.delete()
            mutable.update { it.copy(importing = false) }
        }
    }

    suspend fun downloadAndroid(context: Context) {
        if (state.value.running || state.value.importing) return
        mutable.update { it.copy(importing = true, message = "Скачивание Android-x86 5.1 (358 МБ)…") }
        val temp = File(directory(context), "guest.iso.part")
        val connection = URL("https://downloads.sourceforge.net/project/android-x86/Release%205.1/android-x86-5.1-rc1.iso")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 15000
            require(connection.responseCode == 200) { "Сервер вернул HTTP ${connection.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        require(count <= 375390208L) { "Неверный размер образа" }
                        output.write(buffer, 0, size)
                        digest.update(buffer, 0, size)
                        mutable.update { it.copy(message = "Android 5.1: ${count / 1048576} / 358 МБ") }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(count == 375390208L && hash == "b0d5470b88a030ebc7715a3bdabb9ca7c5dad8b476430a98f3c0fb668e041a6d") {
                "Образ не прошёл проверку SHA-256"
            }
            Files.move(temp.toPath(), iso(context).toPath(), StandardCopyOption.REPLACE_EXISTING)
            mutable.update { it.copy(imageReady = true, message = "Android-x86 5.1 скачан и проверен") }
        } catch (cancelled: CancellationException) {
            mutable.update { it.copy(message = "Скачивание отменено") }
            throw cancelled
        } catch (error: Exception) {
            mutable.update { it.copy(message = error.message ?: "Ошибка скачивания") }
        } finally {
            connection.disconnect()
            temp.delete()
            mutable.update { it.copy(importing = false) }
        }
    }

    private fun asset(context: Context, name: String, destination: File) {
        val temp = File(destination.parentFile, destination.name + ".part")
        context.assets.open("qemu/$name").use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun assetTree(context: Context, name: String, destination: File) {
        val children = context.assets.list("qemu/$name").orEmpty()
        if (children.isEmpty()) {
            asset(context, name, destination)
        } else {
            check(destination.isDirectory || destination.mkdirs()) { "Не удалось создать ${destination.name}" }
            for (child in children) assetTree(context, "$name/$child", File(destination, child))
        }
    }

    /** Called once by the foreground service on an IO thread. */
    fun run(context: Context, demo: Boolean) {
        stopped.set(false)
        mutable.update { it.copy(running = true, message = "Запуск QEMU…", log = "") }
        var child: Process? = null
        try {
            require(Build.SUPPORTED_ABIS.contains("arm64-v8a")) { "Этот QEMU собран для ARM64-телефонов" }
            val libDir = context.applicationInfo.nativeLibraryDir
            val binary = File(libDir, "libvine_qemu_i386.so")
            require(binary.canExecute()) { "QEMU не извлечён установщиком: ${binary.name}" }
            val dir = directory(context)
            val firmware = File(dir, "firmware").also { it.mkdirs() }
            assetTree(context, "firmware", firmware)
            require(File(firmware, "keymaps/en-us").isFile) { "В сборке отсутствует раскладка QEMU en-us" }
            val image = if (demo) File(dir, "demo.img").also { asset(context, "demo.img", it) } else iso(context)
            require(image.isFile) { "Сначала выбери ISO Android-x86" }
            if (stopped.get()) return
            val builder = ProcessBuilder(QemuCommand.build(binary.absolutePath, firmware.absolutePath, image.absolutePath, demo))
                .directory(dir)
                .redirectErrorStream(true)
            builder.environment().apply {
                put("LD_LIBRARY_PATH", libDir)
                put("HOME", dir.absolutePath)
                put("TMPDIR", context.cacheDir.absolutePath)
                put("QEMU_MODULE_DIR", libDir)
                put("GIO_MODULE_DIR", File(dir, "no-gio-modules").absolutePath)
            }
            child = builder.start()
            process = child
            if (stopped.get()) child.destroy()
            mutable.update { it.copy(message = if (demo) "Пробная гостевая машина" else "Загрузка ISO Android-x86") }
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> mutable.update { it.copy(log = (it.log + line + "\n").takeLast(16000)) } }
            }
            val code = child.waitFor()
            mutable.update { it.copy(message = if (stopped.get()) "Остановлено" else "QEMU завершился, код $code") }
        } catch (error: Exception) {
            mutable.update { it.copy(message = "Ошибка запуска", log = (it.log + (error.message ?: error.toString())).takeLast(16000)) }
        } finally {
            child?.destroy()
            process = null
            mutable.update { it.copy(running = false) }
        }
    }

    fun stop() {
        stopped.set(true)
        process?.destroy()
    }
}
