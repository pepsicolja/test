package com.hexadecinull.vineos.qemu.arm

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

internal class ArmFiles(context: Context) {
    private val context = context.applicationContext
    val root = File(context.filesDir, "android5-arm32")
    val system get() = File(root, "system.img")
    val data get() = File(root, "userdata.img")
    val cache get() = File(root, "cache.img")
    val kernel get() = File(root, "kernel-ranchu")
    val runtime get() = File(root, "runtime")
    val ramdisk get() = File(root, "ramdisk.img")
    val ready: Boolean get() = runCatching {
        File(root, "ready").readText() == IMAGE_ID &&
            listOf(system, data, cache, kernel, ramdisk).all { it.isFile && it.length() > 0 }
    }.getOrDefault(false)

    fun prepareRuntime() {
        val maps = File(runtime, "keymaps")
        check(maps.isDirectory || maps.mkdirs()) { "Не удалось подготовить раскладки ввода" }
        val names = context.assets.list("qemu/firmware/keymaps").orEmpty()
        check("en-us" in names) { "В APK отсутствует раскладка en-us" }
        for (name in names) {
            context.assets.open("qemu/firmware/keymaps/$name").use { input ->
                File(maps, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    suspend fun prepare(status: (String) -> Unit) {
        if (ready) return
        check(!root.exists()) { "Данные гостя уже существуют. Они сохранены; подготовка поверх них запрещена." }
        val archive = download(status)
        val stage = File(context.filesDir, ".arm32-preparing")
        stage.deleteRecursively()
        check(stage.mkdirs()) { "Не удалось создать хранилище гостя" }
        try {
            status("Подготовка дисков Android…")
            val found = mutableSetOf<String>()
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    val name = when (entry.name) {
                        "armeabi-v7a/system.img" -> "system.img"
                        "armeabi-v7a/userdata.img" -> "userdata.img"
                        "armeabi-v7a/ramdisk.img" -> "ramdisk.img"
                        "armeabi-v7a/NOTICE.txt" -> "NOTICE.txt"
                        else -> null
                    }
                    if (name != null) {
                        check(found.add(name)) { "Повторный файл в образе" }
                        val expected = when (name) {
                            "system.img" -> SYSTEM_SIZE to SYSTEM_SHA
                            "userdata.img" -> DATA_SIZE to DATA_SHA
                            "NOTICE.txt" -> 1603972L to "3b261007c5136db575b2c93c60e6dc0daadb8c797f2206a24aa173f64ac2b196"
                            else -> 806576L to "293f60c1dc08adfcdfbf2fe4c37f73ab4b75c5a84a9cc25f15001878630715dd"
                        }
                        sparse(zip, File(stage, name), expected.first, expected.second)
                    }
                    zip.closeEntry()
                }
            }
            check(found.size == 4) { "Архив Android не содержит нужные диски" }
            val manifest = JSONObject(context.assets.open("arm32/manifest.json").bufferedReader().use { it.readText() })
            val boot = File(stage, "ramdisk.img")
            boot.writeBytes(ArmRamdisk.patch(boot.readBytes()))
            status("Дополнительные файлы Android…")
            downloadKernel(File(stage, "kernel-ranchu"))
            val cacheInfo = manifest.getJSONObject("cache.img")
            GZIPInputStream(context.assets.open("arm32/cache.img.bin")).use { source ->
                sparse(source, File(stage, "cache.img"), cacheInfo.getLong("size"), cacheInfo.getString("sha256"))
            }
            // The full factory image hash was verified above. This is the pinned
            // EGL empty-config return instruction; user APKs remain unchanged.
            RandomAccessFile(File(stage, "system.img"), "rw").use { image ->
                image.seek(785610434L)
                check(image.readUnsignedByte() == 0xd4) { "Неизвестная версия драйвера EGL" }
                image.seek(785610434L)
                image.writeByte(0xb4)
                image.fd.sync()
            }
            File(stage, "ready").writeText(IMAGE_ID)
            currentCoroutineContext().ensureActive()
            check(stage.renameTo(root)) { "Не удалось завершить подготовку гостя" }
            archive.delete()
            status("Android 5.1 ARM32 подготовлен")
        } finally {
            stage.deleteRecursively()
        }
    }

    private suspend fun downloadKernel(target: File) {
        // The default image has the Ranchu HALs but no Ranchu kernel. Fetch the
        // matching public kernel entry without downloading the 377 MiB Google APIs image.
        val connection = URL(KERNEL_ARCHIVE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Range", "bytes=0-3000000")
        try {
            check(connection.responseCode == 206 && connection.getHeaderField("Content-Range")?.startsWith("bytes 0-3000000/") == true) {
                "Не удалось скачать дополнительные файлы Android (HTTP ${connection.responseCode})"
            }
            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: error("В архиве не найдено ядро ARM32")
                    if (entry.name == "armeabi-v7a/kernel-ranchu") {
                        sparse(zip, target, 2832936, KERNEL_SHA)
                        return
                    }
                    zip.closeEntry()
                }
            }
        } finally { connection.disconnect() }
    }

    private suspend fun download(status: (String) -> Unit): File {
        val archive = File(context.cacheDir, "android5-arm32.zip")
        val part = File(context.cacheDir, "android5-arm32.zip.part")
        if (archive.isFile && verifyArchive(archive)) return archive
        archive.delete()
        if (part.length() > ARCHIVE_SIZE) part.delete()
        var offset = part.length()
        if (offset < ARCHIVE_SIZE) {
            val connection = URL(ARCHIVE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                val code = connection.responseCode
                check(code == 200 || code == 206) { "Скачивание Android: HTTP $code" }
                if (code == 200) offset = 0
                else check(connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) {
                    "Сервер вернул неверный диапазон загрузки"
                }
                connection.inputStream.use { source ->
                    RandomAccessFile(part, "rw").use { dest ->
                        if (offset == 0L) dest.setLength(0)
                        dest.seek(offset)
                        val buffer = ByteArray(256 * 1024)
                        var shown = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = source.read(buffer)
                            if (n < 0) break
                            check(offset + n <= ARCHIVE_SIZE) { "Размер архива Android превышен" }
                            dest.write(buffer, 0, n)
                            offset += n
                            val percent = (offset * 100 / ARCHIVE_SIZE).toInt()
                            if (percent != shown) { shown = percent; status("Скачивание Android: $percent% · 186 МБ") }
                        }
                    }
                }
            } finally { connection.disconnect() }
        }
        status("Проверка архива Android…")
        if (!verifyArchive(part)) {
            part.delete()
            error("Архив Android повреждён или скачан не полностью")
        }
        check(part.renameTo(archive)) { "Не удалось сохранить архив Android" }
        return archive
    }

    private suspend fun verifyArchive(file: File): Boolean {
        if (file.length() != ARCHIVE_SIZE) return false
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { source ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = source.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex() == ARCHIVE_SHA
    }

    private suspend fun sparse(source: InputStream, target: File, size: Long, sha: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        RandomAccessFile(target, "rw").use { out ->
            out.setLength(0)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = source.read(buffer)
                if (n < 0) break
                check(total + n <= size) { "Размер диска Android превышен" }
                digest.update(buffer, 0, n)
                var zero = true
                for (i in 0 until n) if (buffer[i] != 0.toByte()) { zero = false; break }
                if (zero) out.seek(out.filePointer + n) else out.write(buffer, 0, n)
                total += n
            }
            out.setLength(total)
            check(total == size && digest.digest().hex() == sha) { "Не совпала контрольная сумма ${target.name}" }
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val IMAGE_ID = "android5-arm32-ranchu-1"
        const val ARCHIVE_URL = "https://dl.google.com/android/repository/sys-img/android/armeabi-v7a-22_r02.zip"
        const val ARCHIVE_SIZE = 194596267L
        const val ARCHIVE_SHA = "2114ec015dbf3a16cbcb4f63e8a84a1b206a07a1"
        const val SYSTEM_SIZE = 786432000L
        const val SYSTEM_SHA = "5a826efb89e39827396bce142bf9896b17be6c8f895a5c004186086674900909"
        const val DATA_SIZE = 576716800L
        const val DATA_SHA = "a19b6eff4b76722a99ec40505a5794b3e6ef4bba443779d8afd28607cf8abb8c"
        const val KERNEL_ARCHIVE_URL = "https://dl.google.com/android/repository/sys-img/google_apis/armeabi-v7a-22_r26.zip"
        const val KERNEL_SHA = "fc36e97ba9a4f89160d41e93d8f7e67a5a8e51a76108329c21da0fb86ba6b2a2"
    }
}
