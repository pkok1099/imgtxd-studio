package com.onlasdan.studio.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Engine benchmark over the CPU-hot native paths — DXT codec, mipmap
 * generation, IMG rebuild — all served by the Rust core since the
 * full-Rust migration (2026-10-23); Kotlin only times the calls.
 */
object EngineBenchmark {

    private fun gradientPixels(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = (x * 255) / width
                val g = (y * 255) / height
                val b = ((x + y) * 255) / (width + height)
                pixels[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return pixels
    }

    suspend fun run(context: Context): String = withContext(Dispatchers.Default) {
        val lines = mutableListOf<String>()
        lines += "Perangkat: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"
        lines += "CPU cores: ${Runtime.getRuntime().availableProcessors()}"
        lines += ""

        val rustVersion = NativeCore.version()
        if (rustVersion == null) {
            lines += "Rust core: tidak tersedia di build ini — benchmark dibatalkan"
            return@withContext lines.joinToString("\n")
        }
        lines += "Rust core: $rustVersion"
        lines += ""

        // 1. Mipmap chain from 2048x2048 (bilinear, filter ordinal 2)
        val src = gradientPixels(2048, 2048)
        var chainSize = 0
        val mipMs = measureTimeMillis {
            var cur = src
            var w = 2048
            var h = 2048
            while (w > 1 || h > 1) {
                val next = NativeCore.mipmapHalf(cur, w, h, 2) ?: break
                w = maxOf(1, w / 2)
                h = maxOf(1, h / 2)
                chainSize += w * h
                cur = next
            }
        }
        lines += "Mipmap chain 2048² bilinear: $mipMs ms (${chainSize / 1_000_000.0} Mpx total)"

        // 2. DXT1 encode + decode 1024x1024
        val img1024 = gradientPixels(1024, 1024)
        var encoded: ByteArray? = null
        val encMs = measureTimeMillis {
            encoded = NativeCore.encodeDxt1(img1024, 1024, 1024)
        }
        val data = encoded ?: ByteArray(0)
        val decMs = measureTimeMillis {
            NativeCore.decodeDxt1(data, 1024, 1024)
        }
        lines += "DXT1 encode 1024²: $encMs ms · decode 1024²: $decMs ms"

        // 3. DXT1 encode throughput 256² x 50
        val img256 = gradientPixels(256, 256)
        val thrMs = measureTimeMillis {
            repeat(50) { NativeCore.encodeDxt1(img256, 256, 256) }
        }
        lines += "DXT1 encode 256² ×50: $thrMs ms (${"%.1f".format(50_000.0 / maxOf(thrMs, 1))} tekstur/detik)"

        // 4. IMG: build 100 entries x 128 KiB, rebuild, reopen
        val dir = File(context.cacheDir, "bench_img").apply { mkdirs() }
        try {
            val payload = File(dir, "payload.bin")
            payload.writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() })
            val imgFile = File(dir, "bench.img")
            val addMs = measureTimeMillis {
                val archive = ImgArchive.createEmpty(imgFile, ImgVersion.VER2)
                repeat(100) { i -> archive.add(payload, "bench_$i.dff") }
            }
            val rebuildMs = measureTimeMillis {
                val archive = ImgArchive.open(imgFile)
                archive.rebuild()
            }
            var entryCount = 0
            val openMs = measureTimeMillis {
                entryCount = ImgArchive.open(imgFile).entries.size
            }
            lines += "IMG 100×128KiB: tambah $addMs ms · rebuild $rebuildMs ms · buka+parse ($entryCount entri) $openMs ms"
        } finally {
            dir.deleteRecursively()
        }

        lines.joinToString("\n")
    }
}
