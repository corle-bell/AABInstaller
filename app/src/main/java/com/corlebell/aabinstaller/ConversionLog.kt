package com.corlebell.aabinstaller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 主页面与链接安装页面共用的转换日志。 */
object ConversionLog {

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    @Synchronized
    fun reset() {
        _content.value = ""
    }

    @Synchronized
    fun append(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _content.value += "[$time] $message\n"
    }
}
