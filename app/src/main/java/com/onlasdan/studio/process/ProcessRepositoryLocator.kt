package com.onlasdan.studio.process

import android.content.Context
import com.onlasdan.studio.worker.repo.OrphanJobRecovery
import com.onlasdan.studio.worker.repo.ProcessRepositoryStrategy

/**
 * Service locator terpusat untuk ProcessRepository.
 * Mengarahkan seluruh pemanggilan operasi latar belakang ke ProcessRepositoryStrategy
 * (yang secara dinamis mendelegasikan ke :worker service atau in-process fallback).
 */
object ProcessRepositoryLocator {

    @Volatile
    private var repository: ProcessRepository? = null

    fun get(context: Context? = null): ProcessRepository {
        return repository ?: synchronized(this) {
            repository ?: (context?.let { ProcessRepositoryStrategy.getInstance(it.applicationContext) }
                ?: DefaultProcessRepository).also {
                repository = it
            }
        }
    }

    fun init(context: Context) {
        val strat = ProcessRepositoryStrategy.getInstance(context.applicationContext)
        repository = strat
        try {
            OrphanJobRecovery.recoverOrphanJobs(context.applicationContext)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
