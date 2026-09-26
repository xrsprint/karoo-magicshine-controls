package com.lenne0815.magicshinesniffer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FrameRecorder(private val context: Context) {
    val fileName: String = "magicshine-ble-" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + ".txt"
    private var uri: android.net.Uri? = null
    private var stream: OutputStream? = null
    private val legacyFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)

    @Synchronized
    fun append(line: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Magicshine")
                }
                uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                stream = uri?.let { context.contentResolver.openOutputStream(it, "wa") }
            }
            stream?.write((line + "\n").toByteArray(Charsets.UTF_8))
            stream?.flush()
        } else {
            legacyFile.parentFile?.mkdirs()
            legacyFile.appendText(line + "\n")
        }
    }
}