package com.hexadecinull.vineos.qemu.arm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.Surface
import androidx.core.content.ContextCompat
import com.hexadecinull.vineos.qemu.QemuEngine
import com.hexadecinull.vineos.qemu.RfbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

internal data class ArmState(
    val prepared: Boolean = false,
    val busy: Boolean = false,
    val running: Boolean = false,
    val booted: Boolean = false,
    val surface: Boolean = false,
    val status: String = "Подготовь Android перед первым запуском",
    val log: String = "",
)

internal object ArmEngine {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val mutable = MutableStateFlow(ArmState())
    val state = mutable.asStateFlow()
    private var prepareJob: Job? = null
    private var importJob: Job? = null
    private var surface: Surface? = null
    @Volatile private var process: Process? = null
    @Volatile private var rfb: RfbClient? = null
    @Volatile private var inputWidth = ArmCommand.WIDTH
    @Volatile private var inputHeight = ArmCommand.HEIGHT
    @Volatile private var adbPort = 0
    private data class Touch(val x: Float, val y: Float, val down: Boolean)
    private val touches = Channel<Touch>(Channel.UNLIMITED)

    init {
        worker.launch {
            for (touch in touches) runCatching {
                val x = (touch.x * inputWidth).toInt().coerceIn(0, inputWidth - 1)
                val y = (touch.y * inputHeight).toInt().coerceIn(0, inputHeight - 1)
                rfb?.pointer(x, y, touch.down)
            }
        }
    }

    fun initialize(context: Context) { mutable.update { it.copy(prepared = ArmFiles(context).ready) } }
    fun touch(x: Float, y: Float, down: Boolean) { touches.trySend(Touch(x, y, down)) }
    private fun message(value: String) { mutable.update { it.copy(status = value) } }
    private fun log(value: String) { mutable.update { it.copy(log = (it.log + "\n" + value.take(1024)).takeLast(12000)) } }

    fun prepare(context: Context) {
        if (state.value.busy || state.value.running) return
        mutable.update { it.copy(busy = true) }
        prepareJob = worker.launch {
            try {
                ArmFiles(context).prepare(::message)
                mutable.update { it.copy(prepared = true) }
            } catch (cancel: CancellationException) {
                message("Подготовка отменена"); throw cancel
            } catch (error: Exception) { message(error.message ?: "Ошибка подготовки Android") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun cancelPreparation() { prepareJob?.cancel() }

    fun attach(value: Surface) {
        worker.launch {
            lifecycle.withLock {
                surface = value
                mutable.update { it.copy(surface = value.isValid) }
                if (process?.isAlive == true && value.isValid) runCatching {
                    ArmRenderer.detach()
                    ArmRenderer.start(value, ArmCommand.WIDTH, ArmCommand.HEIGHT)
                }.onFailure { message(it.message ?: "Ошибка экрана") }
            }
        }
    }

    fun detach(value: Surface, release: () -> Unit = {}) {
        worker.launch {
            lifecycle.withLock {
                try {
                    if (surface === value) {
                        surface = null
                        mutable.update { it.copy(surface = false) }
                        if (process?.isAlive == true) runCatching { ArmRenderer.detach() }
                    }
                } finally { release() }
            }
        }
    }

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, ArmVmService::class.java))
    }

    suspend fun run(context: Context) = withContext(Dispatchers.IO) {
        var mine: Process? = null
        var inputJob: Job? = null
        var bootJob: Job? = null
        try {
            lifecycle.withLock {
                if (process?.isAlive == true) return@withContext
                check(Build.VERSION.SDK_INT >= 28 && "arm64-v8a" in Build.SUPPORTED_ABIS) {
                    "Этот движок требует ARM64 и Android 9 или новее"
                }
                val files = ArmFiles(context)
                check(files.ready) { "Сначала подготовь Android" }
                val display = surface
                check(display != null && display.isValid) { "Экран ещё не готов" }
                QemuEngine.stop()
                GuestAdb.disconnect()
                val port = ArmRenderer.start(display, ArmCommand.WIDTH, ArmCommand.HEIGHT)
                check(port in 1..65535) { "Не запустился графический модуль" }
                log("Host renderer: ${ArmRenderer.info()}")
                val libraryDir = File(context.applicationInfo.nativeLibraryDir)
                val binary = File(libraryDir, "libvine_qemu_arm.so")
                check(binary.isFile) { "В APK отсутствует ARM-движок" }
                adbPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val builder = ProcessBuilder(ArmCommand.build(binary.absolutePath, files, adbPort))
                    .directory(files.root).redirectErrorStream(true)
                builder.environment().apply {
                    put("LD_LIBRARY_PATH", libraryDir.absolutePath)
                    put("TMPDIR", context.cacheDir.absolutePath)
                    put("VINE_GLES_PORT", port.toString())
                    put("QEMU_AUDIO_DRV", "aaudio")
                }
                mine = builder.start()
                process = mine
                mutable.update { it.copy(running = true, booted = false, status = "Загрузка Android… Первый запуск может занять несколько минут.") }
            }
            val active = mine ?: return@withContext
            val client = RfbClient(port = ArmCommand.RFB_PORT) { width, height, _ ->
                inputWidth = width; inputHeight = height
            }
            rfb = client
            inputJob = worker.launch {
                runCatching { client.run() }.onFailure { if (active.isAlive) log("Input: ${it.message}") }
            }
            bootJob = worker.launch {
                while (active.isAlive) {
                    val booted = runCatching { GuestAdb(adbPort, 5000).shell("getprop sys.boot_completed").trim() == "1" }.getOrDefault(false)
                    if (booted) {
                        val abi = runCatching { GuestAdb(adbPort).shell("getprop ro.product.cpu.abi").trim() }.getOrDefault("")
                        if (abi == "armeabi-v7a" || abi == "armeabi") {
                            mutable.update { it.copy(booted = true, status = "Android 5.1 ARM32 загружен") }
                            // Synchronize only the virtual guest's clock; this does not elevate the phone app.
                            val now = java.text.SimpleDateFormat("yyyyMMdd.HHmmss", java.util.Locale.US).format(java.util.Date())
                            runCatching { GuestAdb(adbPort).shell("su 0 date -s $now") }
                        } else { message("Неожиданная архитектура гостя: $abi") }
                        break
                    }
                    delay(5000)
                }
            }
            active.inputStream.bufferedReader().useLines { lines -> lines.forEach(::log) }
            val code = active.waitFor()
            message("Гость остановлен (код $code)")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) { message(error.message ?: "Не удалось запустить Android") }
        catch (error: LinkageError) { message("Не удалось загрузить ARM/GLES модуль: ${error.message}") }
        finally {
            withContext(NonCancellable) {
                lifecycle.withLock {
                    if (mine != null && process === mine || mine == null && process == null) {
                        mine?.let { child ->
                            if (child.isAlive) child.destroy()
                            if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly()
                        }
                        GuestAdb.disconnect()
                        rfb?.close(); rfb = null
                        inputJob?.cancel(); bootJob?.cancel()
                        importJob?.cancel()
                        runCatching { ArmRenderer.stop() }
                        process = null
                        mutable.update { it.copy(running = false, booted = false, busy = false) }
                    }
                }
            }
        }
    }

    fun stop() {
        val active = process ?: return
        message("Остановка гостя…")
        GuestAdb.disconnect()
        importJob?.cancel()
        worker.launch {
            runCatching { GuestAdb(adbPort, 2000).shell("sync") }
            active.destroy()
            delay(2000)
            if (active.isAlive) active.destroyForcibly()
        }
    }

    fun key(code: Int) {
        if (state.value.booted) worker.launch {
            runCatching { GuestAdb(adbPort).shell("input keyevent $code") }
                .onFailure { message("Не отправлена кнопка: ${it.message}") }
        }
    }

    @Suppress("DEPRECATION")
    fun install(context: Context, uri: Uri) {
        if (!state.value.booted || state.value.busy) return
        mutable.update { it.copy(busy = true, status = "Копирование APK…") }
        importJob = worker.launch {
            val file = File(context.cacheDir, "guest-import.apk")
            try {
                context.contentResolver.openInputStream(uri).use { source ->
                    checkNotNull(source) { "Не удалось открыть APK" }
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val n = source.read(buf); if (n < 0) break
                            total += n; check(total <= 512L * 1024 * 1024) { "APK больше 512 МБ" }
                            out.write(buf, 0, n)
                        }
                    }
                }
                val pkg = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
                check(pkg != null && pkg.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Не удалось прочитать пакет APK" }
                val adb = GuestAdb(adbPort, 300_000)
                message("Установка APK в Android 5…")
                file.inputStream().use { adb.push(it, "/data/local/tmp/vineos-import.apk") }
                val result = adb.shell("pm install -r /data/local/tmp/vineos-import.apk")
                log(result)
                check("Success" in result) { result.ifBlank { "Установка в госте не завершилась" } }
                adb.shell("rm /data/local/tmp/vineos-import.apk")
                message("Запуск $pkg…")
                log(adb.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1"))
                message("APK установлен: $pkg")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { message(error.message ?: "Ошибка установки APK") }
            finally { file.delete(); mutable.update { it.copy(busy = false) } }
        }
    }
}
