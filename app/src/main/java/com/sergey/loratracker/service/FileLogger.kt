package com.sergey.loratracker.service

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "lora_logs")
        dir.mkdirs()
        val name = "lora_${fileDateFormat.format(Date())}.txt"
        logFile = File(dir, name)
        write("LOG STARTED")
    }

    fun write(msg: String) {
        val line = "${dateFormat.format(Date())} | $msg\n"
        try {
            logFile?.appendText(line)
        } catch (_: Exception) {}
    }

    fun d(tag: String, msg: String) = write("D/$tag: $msg")
    fun e(tag: String, msg: String) = write("E/$tag: $msg")
    fun e(tag: String, msg: String, tr: Throwable?) {
        val stack = tr?.stackTraceToString()?.lines()?.take(5)?.joinToString("\n") ?: "null"
        write("E/$tag: $msg\n$stack")
    }
    fun w(tag: String, msg: String) = write("W/$tag: $msg")
}
