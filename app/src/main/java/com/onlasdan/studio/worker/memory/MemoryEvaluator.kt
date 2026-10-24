package com.onlasdan.studio.worker.memory

import android.app.ActivityManager
import android.content.Context
import com.onlasdan.studio.worker.MemoryBudget
import com.onlasdan.studio.worker.WorkerContracts
import kotlin.math.max
import kotlin.math.min

/**
 * Representasi statistik memori fisik dan alokasi heap sistem Android.
 *
 * @property totalMem Total memori fisik RAM perangkat dalam satuan bytes.
 * @property availMem Memori fisik RAM yang sedang tersedia/bebas dalam satuan bytes.
 * @property memoryClass Batas alokasi heap standar aplikasi per proses dalam satuan MB.
 * @property largeMemoryClass Batas alokasi heap besar (android:largeHeap="true") dalam satuan MB.
 * @property isLowRamDevice Indikator apakah perangkat tergolong low-RAM (misal Android Go atau perangkat ram terbatas).
 * @property threshold Ambang batas memori fisik di mana sistem Android mulai membunuh background process (bytes).
 * @property lowMemory Flag sistem yang menandakan perangkat sedang berada dalam kondisi memori kritis.
 */
data class DeviceMemoryStats(
    val totalMem: Long,
    val availMem: Long,
    val memoryClass: Int,
    val largeMemoryClass: Int,
    val isLowRamDevice: Boolean,
    val threshold: Long = 0L,
    val lowMemory: Boolean = false
) {
    val totalMemMb: Long get() = totalMem / (1024L * 1024L)
    val availMemMb: Long get() = availMem / (1024L * 1024L)
    val thresholdMb: Long get() = threshold / (1024L * 1024L)
}

/**
 * Evaluator dan pengatur alokasi anggaran memori adaptif untuk runtime proses ImgTxd Studio.
 *
 * ====================================================================================================
 * ANALISIS TEKNIS: Evaluasi Arsitektur Child Process (-Xmx) vs Android Service (:worker)
 * (Konteks perbandingan dengan pendekatan ReVanced / Morphe CLI di Android Modern)
 * ====================================================================================================
 *
 * 1. Pendekatan Child Process Mandiri dengan Flag -Xmx (ala ReVanced / Morphe CLI):
 *    - Mekanisme: Aplikasi induk mem-fork child process atau memanggil binary JVM/Dalvik terpisah
 *      (misalnya melalui ProcessBuilder atau Runtime.getRuntime().exec) dengan memberikan argumen
 *      baris perintah pembatas heap JVM seperti `-Xmx512M` atau `-Xmx1024M`.
 *    - Kelebihan:
 *      * Isolasi Total Heap & Native Memory: Crash karena heap exhaustion (OutOfMemoryError) atau
 *        segfault pada library native C/C++ murni terisolasi di child process, sehingga UI proses utama
 *        (main application) tidak pernah mengalami crash/ANR.
 *      * Ruang Memori Virtual Mandiri: Manajemen ruang alamat memori virtual dan alokasi garbage collection
 *        berjalan sepenuhnya di luar batas Dalvik VM aplikasi induk.
 *    - Risiko & Hambatan Kritis di Android 14, 15, dan Android 16 (API 34 - 36):
 *      * Kebijakan Ketat SELinux & W^X Violation: Android modern secara ketat memberlakukan pembatasan
 *        eksekusi kode biner di direktori data aplikasi (noexec mount pada app sandbox storage).
 *        Mem-fork dan mengeksekusi biner eksternal atau script shell sering memicu `avc: denied { execute }`
 *        atau crash akibat pelanggaran W^X (Write XOR Execute).
 *      * Overhead Startup JVM Runtime Kedua: Menginisialisasi instance Android Runtime (ART) atau JVM
 *        kedua memerlukan alokasi threadpool runtime, loading boot classpath, dan verifikasi class
 *        yang memakan waktu signifikan (ratusan milidetik hingga detik) serta konsumsi RAM redundan.
 *      * Latensi & Overhead IPC Antar Proses: Mengalirkan data file besar (ratusan MB hingga GB)
 *        melalui stdin/stdout stream POSIX tanpa saluran IPC native Android rentan terhadap buffer
 *        starvation, pipeline blocking, dan throughput lambat.
 *      * Phantom Process Killer: Sejak Android 12 hingga Android 16, subsistem Android memantau
 *        child process yang di-spawn oleh aplikasi. Jika child process mengonsumsi CPU/RAM berlebih
 *        di luar kendali ActivityManager/JobScheduler, sistem akan secara sepihak mematikan child process
 *        tersebut (phantom process trim).
 *
 * 2. Solusi Terpilih: Android Service Terpisah (`android:process=":worker"`) + `android:largeHeap="true"`
 *    - Desain: Memanfaatkan komponen Android Service standar yang dikonfigurasikan berjalan pada proses
 *      terpisah (`android:process=":worker"`). Proses ini di-fork langsung oleh Zygote platform.
 *    - Keunggulan Utama:
 *      * Kepatuhan Penuh Sandbox & SELinux: Proses di-fork secara legal oleh Zygote dengan security context
 *        yang sah, kompatibel 100% pada Android 14, 15, dan 16 (Baklava) tanpa risiko pembatasan noexec.
 *      * Alokasi Heap Maksimal: Deklarasi `android:largeHeap="true"` memberikan ruang heap ART hingga
 *        mencapai `largeMemoryClass` (misal 512 MB - 1024 MB pada perangkat modern).
 *      * Komunikasi IPC Native & Lifecycle Terkelola: Terintegrasi dengan Binder IPC, AIDL, Foreground
 *        Service notification, dan listener `ComponentCallbacks2.onTrimMemory` untuk adaptasi real-time.
 *      * Alokasi Buffer Adaptif (Dynamic Budgeting): Ukuran I/O buffer, chunk size, ukuran cache LRU,
 *        dan tingkat paralelisme disesuaikan secara real-time berdasarkan ketersediaan RAM (`availMem`)
 *        dan memori aman perangkat, mencegah pemicuan Android Low Memory Killer (LMK).
 *    - Kesimpulan:
 *      Menggunakan Android Service terpisah (`android:process=":worker"`) dengan `android:largeHeap="true"`
 *      dan kontrol I/O buffer dinamis adalah arsitektur paling stabil, berkinerja tinggi, aman dari LMK,
 *      dan memenuhi seluruh standar platform Android masa kini dan masa depan.
 * ====================================================================================================
 */
object MemoryEvaluator {

    /**
     * Pesan rekomendasi fallback ketika terjadi OutOfMemoryError.
     */
    const val OOM_FALLBACK_SUGGESTION: String =
        "Not enough memory to finish the process. Try Auto Mode or lower the memory allocation in Runtime Process Settings."

    /**
     * Mengambil statistik memori perangkat saat ini melalui [ActivityManager].
     *
     * @param context [Context] aplikasi untuk mengakses [ActivityManager].
     * @return Objek [DeviceMemoryStats] yang berisi informasi RAM fisik dan batasan heap.
     */
    fun evaluateDeviceMemory(context: Context): DeviceMemoryStats {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val memoryClass = activityManager?.memoryClass ?: WorkerContracts.MIN_BUDGET_MB
        val largeMemoryClass = activityManager?.largeMemoryClass ?: memoryClass
        val isLowRam = activityManager?.isLowRamDevice ?: false

        return DeviceMemoryStats(
            totalMem = memoryInfo.totalMem,
            availMem = memoryInfo.availMem,
            memoryClass = memoryClass,
            largeMemoryClass = largeMemoryClass,
            isLowRamDevice = isLowRam,
            threshold = memoryInfo.threshold,
            lowMemory = memoryInfo.lowMemory
        )
    }

    /**
     * Menghitung anggaran memori dinamis (Mode Otomatis) dalam satuan megabytes (MB).
     *
     * Rumus dasar:
     * Budget = min(largeMemoryClass, max(256, (availMem * 0.50).toIntInMB))
     *
     * Menjamin batas aman agar proses intensif tidak memicu Android Low Memory Killer (LMK).
     *
     * @param stats Statistik memori perangkat [DeviceMemoryStats].
     * @return Nilai anggaran memori dalam MB.
     */
    fun calculateAutoBudgetMb(stats: DeviceMemoryStats): Int {
        // availMem * 0.50 dalam megabytes
        val halfAvailMb = (stats.availMem.toDouble() / (1024.0 * 1024.0) * 0.50).toInt()
        val boundedAvail = max(WorkerContracts.MIN_BUDGET_MB, halfAvailMb)
        val candidateBudgetMb = min(stats.largeMemoryClass, boundedAvail)

        // Pengaman tambahan LMK jika perangkat berstatus lowMemory atau tergolong Low-RAM device
        return if (stats.isLowRamDevice || stats.lowMemory) {
            min(candidateBudgetMb, max(128, stats.memoryClass))
        } else {
            candidateBudgetMb
        }
    }

    /**
     * Menghitung anggaran memori dinamis (Mode Otomatis) langsung dari [Context].
     *
     * @param context Context aplikasi.
     * @return Nilai anggaran memori dalam MB.
     */
    fun calculateAutoBudgetMb(context: Context): Int {
        val stats = evaluateDeviceMemory(context)
        return calculateAutoBudgetMb(stats)
    }

    /**
     * Mengonversi nilai anggaran memori (MB) ke konfigurasi nyata [MemoryBudget].
     *
     * Aturan konversi:
     * - ioBufferSize:
     *     - Budget <= 256 MB  -> 64 KB (64 * 1024)
     *     - Budget 257 - 512 MB -> 128 KB (128 * 1024)
     *     - Budget > 512 MB   -> 256 KB (256 * 1024)
     * - chunkSize:
     *     - Budget <= 256 MB  -> 1 MB (1024 * 1024)
     *     - Budget 257 - 512 MB -> 2 MB (2 * 1024 * 1024)
     *     - Budget > 512 MB   -> 4 MB (4 * 1024 * 1024)
     * - maxParallelItems:
     *     - Budget <= 256 MB  -> 1 (sekuensial murni)
     *     - Budget 257 - 512 MB -> 2
     *     - Budget > 512 MB   -> 4
     * - maxCacheSizeMb: (Budget * 0.15).toInt().coerceIn(16, 128)
     *
     * @param budgetMb Besaran anggaran memori dalam MB.
     * @return Objek konfigurasi [MemoryBudget].
     */
    fun createBudgetFromMb(budgetMb: Int): MemoryBudget {
        val safeBudgetMb = max(128, budgetMb)

        val ioBufferSize = when {
            safeBudgetMb <= 256 -> 64 * 1024
            safeBudgetMb <= 512 -> 128 * 1024
            else -> 256 * 1024
        }

        val chunkSize = when {
            safeBudgetMb <= 256 -> 1024 * 1024
            safeBudgetMb <= 512 -> 2 * 1024 * 1024
            else -> 4 * 1024 * 1024
        }

        val maxParallelItems = when {
            safeBudgetMb <= 256 -> 1
            safeBudgetMb <= 512 -> 2
            else -> 4
        }

        val maxCacheSizeMb = (safeBudgetMb * 0.15).toInt().coerceIn(16, 128)

        return MemoryBudget(
            ioBufferSize = ioBufferSize,
            chunkSize = chunkSize,
            maxParallelItems = maxParallelItems,
            maxCacheSizeMb = maxCacheSizeMb,
            totalBudgetMb = safeBudgetMb
        )
    }

    /**
     * Mengevaluasi kondisi perangkat saat ini dan langsung menghasilkan konfigurasi [MemoryBudget] optimal.
     *
     * @param context Context aplikasi.
     * @return Objek [MemoryBudget] terhitung.
     */
    fun evaluateAndCreateBudget(context: Context): MemoryBudget {
        val stats = evaluateDeviceMemory(context)
        val budgetMb = calculateAutoBudgetMb(stats)
        return createBudgetFromMb(budgetMb)
    }

    /**
     * Memeriksa error/pengecualian runtime dan memberikan saran mitigasi jika terindikasi OOM.
     *
     * @param error Objek Throwable yang tertangkap.
     * @return Pesan saran mitigasi jika OOM, atau pesan kesalahan asli jika bukan OOM.
     */
    fun handleOomOrSuggestFallback(error: Throwable): String {
        return if (isOutOfMemoryError(error)) {
            OOM_FALLBACK_SUGGESTION
        } else {
            error.localizedMessage ?: error.message ?: "Unexpected error while running the task."
        }
    }

    /**
     * Memeriksa rantai penyebab throwable untuk mendeteksi keberadaan [OutOfMemoryError]
     * atau pesan kehabisan memori.
     */
    fun isOutOfMemoryError(throwable: Throwable?): Boolean {
        var current = throwable
        while (current != null) {
            if (current is OutOfMemoryError) return true
            if (current.message?.contains("OutOfMemory", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }
}
