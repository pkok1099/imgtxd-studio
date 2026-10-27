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

    private val _isTexdbOpen = MutableStateFlow(false)
    val isTexdbOpen = _isTexdbOpen.asStateFlow()

    private val _activeTexdbDir = MutableStateFlow<String?>(null)
    val activeTexdbDir = _activeTexdbDir.asStateFlow()

    private val _activeTexdbGameDir = MutableStateFlow<String?>(null)
    val activeTexdbGameDir = _activeTexdbGameDir.asStateFlow()

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

    fun setTexdbActive(dir: String, gameDir: String? = null) {
        _isTexdbOpen.value = true
        _activeTexdbDir.value = dir
        _activeTexdbGameDir.value = gameDir
    }

    fun setTexdbClosed() {
        _isTexdbOpen.value = false
        _activeTexdbDir.value = null
        _activeTexdbGameDir.value = null
    }
}
