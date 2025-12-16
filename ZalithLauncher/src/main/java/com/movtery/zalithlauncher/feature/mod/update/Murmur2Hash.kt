package com.movtery.zalithlauncher.feature.mod.update

import java.io.File
import java.io.FileInputStream

object Murmur2Hash {
    private const val M: Int = 0x5bd1e995.toInt()
    private const val R = 24
    private const val SEED: Int = 1
    
    private val WHITESPACE_CHARS = intArrayOf(0x09, 0x0A, 0x0D, 0x20)
    
    fun computeFingerprint(file: File): Long {
        val data = readFileNormalized(file)
        return computeHash(data).toLong() and 0xFFFFFFFFL
    }
    
    private fun readFileNormalized(file: File): ByteArray {
        val result = mutableListOf<Byte>()
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                for (i in 0 until bytesRead) {
                    val b = buffer[i].toInt() and 0xFF
                    if (b !in WHITESPACE_CHARS) {
                        result.add(buffer[i])
                    }
                }
            }
        }
        return result.toByteArray()
    }
    
    private fun computeHash(data: ByteArray): Int {
        val length = data.size
        var h = SEED xor length
        
        var i = 0
        while (i + 4 <= length) {
            var k = (data[i].toInt() and 0xFF) or
                    ((data[i + 1].toInt() and 0xFF) shl 8) or
                    ((data[i + 2].toInt() and 0xFF) shl 16) or
                    ((data[i + 3].toInt() and 0xFF) shl 24)
            
            k *= M
            k = k xor (k ushr R)
            k *= M
            
            h *= M
            h = h xor k
            
            i += 4
        }
        
        val remaining = length - i
        if (remaining >= 3) {
            h = h xor ((data[i + 2].toInt() and 0xFF) shl 16)
        }
        if (remaining >= 2) {
            h = h xor ((data[i + 1].toInt() and 0xFF) shl 8)
        }
        if (remaining >= 1) {
            h = h xor (data[i].toInt() and 0xFF)
            h *= M
        }
        
        h = h xor (h ushr 13)
        h *= M
        h = h xor (h ushr 15)
        
        return h
    }
}
