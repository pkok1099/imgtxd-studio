package com.onlasdan.studio.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActiveSessionManager {

    private val _isTxdOpen = MutableStateFlow(false)
    val isTxdOpen = _isTxdOpen.asStateFlow()

    private val _activeTxdName = MutableStateFlow<String?>(null)
    val activeTxdName = _activeTxdName.asStateFlow()

    private val _activeTxdPath = MutableStateFlow<String?>(null)
    val activeTxdPath = _activeTxdPath.asStateFlow()

    private val _isImgOpen = MutableStateFlow(false)
    val isImgOpen = _isImgOpen.asStateFlow()

    private val _activeImgName = MutableStateFlow<String?>(null)
    val activeImgName = _activeImgName.asStateFlow()

    fun setTxdActive(name: String, path: String? = null) {
        _isTxdOpen.value = true
        _activeTxdName.value = name
        _activeTxdPath.value = path
    }

    fun setTxdClosed() {
        _isTxdOpen.value = false
        _activeTxdName.value = null
        _activeTxdPath.value = null
    }

    fun setImgActive(name: String) {
        _isImgOpen.value = true
        _activeImgName.value = name
    }

    fun setImgClosed() {
        _isImgOpen.value = false
        _activeImgName.value = null
    }
}
