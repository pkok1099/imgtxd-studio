package com.onlasdan.studio.worker.memory

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.onlasdan.studio.worker.MemoryBudget
import com.onlasdan.studio.worker.MemoryBudgetProvider
import com.onlasdan.studio.worker.WorkerContracts
import com.onlasdan.studio.worker.workerDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Implementasi penyedia anggaran memori [MemoryBudgetProvider] untuk operasi runtime ImgTxd Studio.
 *
 * Mendukung dua mode:
 * 1. "AUTO"   : Menghitung alokasi buffer, chunk, dan paralelisme secara dinamis sesuai kondisi
 *               RAM fisik dan batasan heap perangkat saat ini via [MemoryEvaluator].
 * 2. "MANUAL" : Menggunakan kuota MB yang dikonfigurasi pengguna, dengan pembatasan aman agar tidak
 *               melampaui batas fisik perangkat (largeMemoryClass) atau di bawah batas minimum sistem.
 *
 * Responsif terhadap tekanan memori sistem melalui [onTrimMemory]:
 * Memangkas buffer dan cache hingga 50% secara instan serta memanggil [System.gc] jika terdeteksi
 * kondisi kritis (TRIM_MEMORY_RUNNING_CRITICAL atau TRIM_MEMORY_COMPLETE).
 *
 * @param context Context Android untuk mengakses preferensi dan sistem memori.
 * @param coroutineScope Scope untuk observasi asinkron preferensi DataStore.
 */
class MemoryBudgetProviderImpl(
    context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : MemoryBudgetProvider {

    private val appContext: Context = context.applicationContext ?: context

    private val sharedPreferences by lazy {
        appContext.getSharedPreferences(WorkerContracts.DATASTORE_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var cachedMode: String = WorkerContracts.DEFAULT_MEMORY_MODE

    @Volatile
    private var cachedManualBudgetMb: Int = WorkerContracts.DEFAULT_MANUAL_BUDGET_MB

    @Volatile
    private var isMemoryTrimmed: Boolean = false

    @Volatile
    private var currentBudget: MemoryBudget? = null

    init {
        // 1. Inisialisasi awal nilai sinkron dari SharedPreferences jika tersedia
        loadPreferencesFromSharedPreferences()

        // 2. Observasi asinkron berkelanjutan dari DataStore untuk pembaruan reaktif
        coroutineScope.launch {
            appContext.workerDataStore.data
                .catch { emit(emptyPreferences()) }
                .collect { preferences ->
                    preferences[stringPreferencesKey(WorkerContracts.PREF_KEY_MEMORY_MODE)]?.let { mode ->
                        if (mode.isNotBlank()) {
                            cachedMode = mode
                        }
                    }
                    preferences[intPreferencesKey(WorkerContracts.PREF_KEY_MANUAL_BUDGET_MB)]?.let { budgetMb ->
                        if (budgetMb > 0) {
                            cachedManualBudgetMb = budgetMb
                        }
                    }
                }
        }
    }

    /**
     * Membaca konfigurasi awal dari SharedPreferences sebagai fallback instan sinkron.
     */
    private fun loadPreferencesFromSharedPreferences() {
        try {
            val mode = sharedPreferences.getString(WorkerContracts.PREF_KEY_MEMORY_MODE, null)
            if (!mode.isNullOrBlank()) {
                cachedMode = mode
            }

            if (sharedPreferences.contains(WorkerContracts.PREF_KEY_MANUAL_BUDGET_MB)) {
                val budget = try {
                    sharedPreferences.getInt(
                        WorkerContracts.PREF_KEY_MANUAL_BUDGET_MB,
                        WorkerContracts.DEFAULT_MANUAL_BUDGET_MB
                    )
                } catch (e: Exception) {
                    sharedPreferences.getString(WorkerContracts.PREF_KEY_MANUAL_BUDGET_MB, null)?.toIntOrNull()
                        ?: WorkerContracts.DEFAULT_MANUAL_BUDGET_MB
                }
                if (budget > 0) {
                    cachedManualBudgetMb = budget
                }
            }
        } catch (e: Exception) {
            // Abaikan kesalahan pembacaan awal, nilai default tetap aktif
        }
    }

    /**
     * Mengambil konfigurasi [MemoryBudget] aktif saat ini berdasarkan mode terpilih (AUTO / MANUAL)
     * dan menerapkan penyesuaian pangkas memori jika perangkat dalam kondisi kritis.
     */
    override fun getCurrentBudget(): MemoryBudget {
        val mode = getEffectiveMode()
        val stats = MemoryEvaluator.evaluateDeviceMemory(appContext)

        val baseBudget = if (mode.equals("MANUAL", ignoreCase = true)) {
            val manualMb = getEffectiveManualBudgetMb()
            // Batasi nilai manual agar tidak melampaui kapasitas fisik maksimum perangkat (largeMemoryClass)
            // dan tidak lebih kecil dari batas minimum kerja aman
            val maxSafeMb = max(WorkerContracts.MIN_BUDGET_MB, stats.largeMemoryClass)
            val boundedMb = manualMb.coerceIn(WorkerContracts.MIN_BUDGET_MB, maxSafeMb)
            MemoryEvaluator.createBudgetFromMb(boundedMb)
        } else {
            // Mode AUTO: hitung dinamis berdasarkan statistik memori saat ini
            val autoMb = MemoryEvaluator.calculateAutoBudgetMb(stats)
            MemoryEvaluator.createBudgetFromMb(autoMb)
        }

        // Terapkan pemangkasan 50% jika kondisi onTrimMemory kritis sedang aktif
        val effectiveBudget = if (isMemoryTrimmed) {
            applyTrimReduction(baseBudget)
        } else {
            baseBudget
        }

        currentBudget = effectiveBudget
        return effectiveBudget
    }

    /**
     * Menangani callback sistem Android ketika memori berada dalam tekanan.
     *
     * Jika [level] mencapai [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] atau
     * [ComponentCallbacks2.TRIM_MEMORY_COMPLETE], buffer dan cache dipangkas 50% secara instan,
     * lalu [System.gc] dipanggil untuk melepaskan objek yang tidak lagi dipakai.
     */
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            isMemoryTrimmed = true

            // Pangkas buffer dan cache hingga 50% secara instan pada anggaran yang tersimpan
            val current = currentBudget ?: getCurrentBudget()
            currentBudget = applyTrimReduction(current)

            // Memicu garbage collection untuk membantu pemulihan heap secepat mungkin
            System.gc()
        }
    }

    /**
     * Mengatur ulang status pemangkasan memori kembali ke alokasi normal.
     */
    fun resetTrimState() {
        isMemoryTrimmed = false
    }

    /**
     * Mengetahui apakah alokasi memori saat ini sedang dipangkas karena tekanan sistem.
     */
    fun isTrimmed(): Boolean = isMemoryTrimmed

    /**
     * Menerapkan pemangkasan 50% pada buffer, chunk, cache, dan total anggaran memori.
     */
    private fun applyTrimReduction(budget: MemoryBudget): MemoryBudget {
        return budget.copy(
            ioBufferSize = (budget.ioBufferSize * 0.50).toInt().coerceAtLeast(32 * 1024),
            chunkSize = (budget.chunkSize * 0.50).toInt().coerceAtLeast(512 * 1024),
            maxParallelItems = (budget.maxParallelItems * 0.50).toInt().coerceAtLeast(1),
            maxCacheSizeMb = (budget.maxCacheSizeMb * 0.50).toInt().coerceAtLeast(16),
            totalBudgetMb = (budget.totalBudgetMb * 0.50).toInt().coerceAtLeast(WorkerContracts.MIN_BUDGET_MB / 2)
        )
    }

    private fun getEffectiveMode(): String {
        val spMode = sharedPreferences.getString(WorkerContracts.PREF_KEY_MEMORY_MODE, null)
        return if (!spMode.isNullOrBlank()) spMode else cachedMode
    }

    private fun getEffectiveManualBudgetMb(): Int {
        if (sharedPreferences.contains(WorkerContracts.PREF_KEY_MANUAL_BUDGET_MB)) {
            val spVal = try {
                sharedPreferences.getInt(
                    WorkerContracts.PREF_KEY_MANUAL_BUDGET_MB,
                    WorkerContracts.DEFAULT_MANUAL_BUDGET_MB
                )
            } catch (e: Exception) {
                sharedPreferences.getString(WorkerContracts.PREF_KEY_MANUAL_BUDGET_MB, null)?.toIntOrNull()
                    ?: cachedManualBudgetMb
            }
            if (spVal > 0) return spVal
        }
        return cachedManualBudgetMb
    }
}
