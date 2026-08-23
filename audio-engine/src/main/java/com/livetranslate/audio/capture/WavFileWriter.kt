package com.livetranslate.audio.capture

import java.io.File
import java.io.RandomAccessFile

object WavFileWriter {

    fun createWavFile(file: File, sampleRate: Int = 16000, channels: Int = 1): RandomAccessFile {
        if (file.exists()) file.delete()
        val raf = RandomAccessFile(file, "rw")
        writeWavHeader(raf, sampleRate, channels, 0)
        return raf
    }

    fun appendPcmData(raf: RandomAccessFile, pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size) {
        try {
            raf.write(pcmData, offset, length)
        } catch (e: Exception) {}
    }

    fun finalizeWavFile(raf: RandomAccessFile, sampleRate: Int = 16000, channels: Int = 1) {
        try {
            val totalAudioLen = raf.length() - 44
            if (totalAudioLen > 0) {
                raf.seek(0)
                writeWavHeader(raf, sampleRate, channels, totalAudioLen)
            }
            raf.close()
        } catch (e: Exception) {}
    }

    private fun writeWavHeader(raf: RandomAccessFile, sampleRate: Int, channels: Int, dataSize: Long) {
        val totalDataLen = dataSize + 36
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // 16 for PCM
        header[20] = 1; header[21] = 0 // PCM = 1
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte(); header[33] = 0 // block align
        header[34] = 16; header[35] = 0 // bits per sample
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        raf.seek(0)
        raf.write(header)
        raf.seek(raf.length())
    }
}
