package com.pause.dance

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

@Throws(IOException::class)
fun copyStream(src: InputStream, dst: OutputStream) {
    val buf = ByteArray(1024)
    var len: Int
    while ((src.read(buf).also { len = it }) > 0) dst.write(buf, 0, len)
    src.close()
    dst.close()
}

@Throws(IOException::class)
fun unzip(zipFile: File?, targetDirectory: File?) {
    ZipInputStream(
        BufferedInputStream(FileInputStream(zipFile))
    ).use { zipInS ->
        var count: Int
        val buffer = ByteArray(8192)
        while (true) {
            val zipEntry = zipInS.nextEntry ?: break
            val file = File(targetDirectory, zipEntry.name)
            val dir = if (zipEntry.isDirectory) file else file.parentFile
            if (!dir.isDirectory && !dir.mkdirs()) throw FileNotFoundException(
                "Failed to ensure directory: " +
                        dir.absolutePath
            )
            if (zipEntry.isDirectory) continue
            FileOutputStream(file).use { fileOutS ->
                while (true) {
                    count = zipInS.read(buffer)
                    if (count == -1) break
                    fileOutS.write(buffer, 0, count)
                }
            }
        }
    }
}