package com.onlasdan.studio

/**
 * Global application constants.
 * Centralizes Intent extras, binary format specifications, request codes,
 * default paths, and UI pagination limits across ImgTxd Studio.
 */
object Constants {

    /**
     * Intent Extra keys used for Activity transitions and IPC intents.
     */
    object IntentExtra {
        const val NAVIGATE_TAB = "com.onlasdan.studio.extra.NAVIGATE_TAB"
        const val FILE_PATH = "com.onlasdan.studio.extra.FILE_PATH"
        const val IS_SHIZUKU = "com.onlasdan.studio.extra.IS_SHIZUKU"
        const val PARENT_ARCHIVE_PATH = "com.onlasdan.studio.extra.PARENT_ARCHIVE_PATH"
        const val PARENT_ENTRY_NAME = "com.onlasdan.studio.extra.PARENT_ENTRY_NAME"
        const val PARENT_RESTRICTED_PATH = "com.onlasdan.studio.extra.PARENT_RESTRICTED_PATH"
        const val PARENT_IS_SHIZUKU = "com.onlasdan.studio.extra.PARENT_IS_SHIZUKU"
        const val CACHE_DIR = "com.onlasdan.studio.extra.CACHE_DIR"
        const val GAME_DIR = "com.onlasdan.studio.extra.GAME_DIR"

        // Specialized activity extras (legacy compatibility)
        const val DATA_FILE_PATH = "extra_data_path"
        const val DATA_IS_SHIZUKU = "extra_data_shizuku"
        const val DFF_FILE_PATH = "extra_dff_path"
        const val DFF_IS_SHIZUKU = "extra_dff_shizuku"

        // Background worker extras
        const val JOB_ID = "extra_job_id"
        const val IS_FOREGROUND = "extra_is_foreground"
    }

    /**
     * RenderWare engine & archive binary format constants.
     */
    object RenderWare {
        const val SECTOR_SIZE = 2048
        const val ENTRY_SIZE = 32
        const val NAME_SIZE = 24

        // RenderWare version identifiers
        const val VERSION_SA = 0x1803FFFF  // RenderWare 3.6.0.3 (San Andreas)
        const val VERSION_VC = 0x0C02FFFF  // RenderWare 3.4.0.3 (Vice City)
        const val VERSION_III = 0x0800FFFF // RenderWare 3.3.0.2 (III)
    }

    /**
     * Request codes for Activity results, permissions, and PendingIntents.
     */
    object RequestCode {
        const val SHIZUKU_PERMISSION = 1001
        const val WATCHDOG_ALARM = 202611
        const val RESUME_ALARM = 202612
    }

    /**
     * Storage & file extensions.
     */
    object Storage {
        const val DEFAULT_ROOT = "/storage/emulated/0"
        val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".bmp", ".webp")
        val ARCHIVE_EXTENSIONS = listOf(".img", ".txd", ".dff", ".col", ".ipl")
    }

    /**
     * UI pagination and buffer limits.
     */
    object Paging {
        const val ARCHIVE_PAGE_SIZE = 100
        const val MAX_HISTORY_RECORDS = 200
    }
}
