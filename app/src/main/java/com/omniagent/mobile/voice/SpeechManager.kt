package com.omniagent.mobile.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Owns the Parakeet TDT v2 (int8, sherpa-onnx NeMo transducer) pipeline:
 * mic capture to 16 kHz mono PCM, one-time model download from the official
 * sherpa-onnx release bundle, and transcription through SherpaOnnx.
 */
class SpeechManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_DIR = "parakeet-tdt-v2-int8"
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"

        private data class RemoteFile(val name: String, val bytes: Long)

        private val REMOTE_FILES = listOf(
            RemoteFile("encoder.int8.onnx", 652_184_296L),
            RemoteFile("decoder.int8.onnx", 7_257_753L),
            RemoteFile("joiner.int8.onnx", 1_739_080L),
            RemoteFile("tokens.txt", 9_384L),
        )
    }

    val modelDir: File get() = File(context.filesDir, MODEL_DIR)
    fun isModelReady(): Boolean = File(modelDir, "tokens.txt").exists() &&
        File(modelDir, "encoder.int8.onnx").exists()

    var lastError: String? = null
        private set

    @Volatile
    var isDownloadingModel: Boolean = false
        private set

    private fun setDownloading(value: Boolean) {
        isDownloadingModel = value
    }

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    private val pcmBuffer = ArrayDeque<Short>()

    fun startRecording(): Boolean {
        if (isRecording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        synchronized(pcmBuffer) { pcmBuffer.clear() }
        rec.startRecording()
        recorder = rec
        isRecording = true
        recordThread = Thread {
            val chunk = ShortArray(1600)
            while (isRecording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    synchronized(pcmBuffer) { repeat(n) { pcmBuffer.addLast(chunk[it]) } }
                }
            }
        }.apply { start() }
        return true
    }

    fun stopRecordingAndGetSamples(): ShortArray {
        isRecording = false
        try {
            recordThread?.join(1500)
        } catch (_: InterruptedException) {
        }
        recordThread = null
        recorder?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        recorder = null
        synchronized(pcmBuffer) {
            val out = ShortArray(pcmBuffer.size)
            var i = 0
            for (s in pcmBuffer) out[i++] = s
            pcmBuffer.clear()
            return out
        }
    }

    suspend fun transcribe(samples: ShortArray): String = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.Default
    ) {
        lastError = null
        if (!isModelReady()) {
            lastError = "Voice model not downloaded yet."
            return@withContext ""
        }
        if (samples.isEmpty()) {
            lastError = "No audio captured."
            return@withContext ""
        }
        try {
            SherpaOnnxBridge.transcribe(modelDir.absolutePath, samples, SAMPLE_RATE)
        } catch (t: Throwable) {
            android.util.Log.e("OmniVoice", "transcription failed", t)
            lastError = "${t::class.simpleName}: ${t.message ?: "Transcription failed"}"
            ""
        }
    }

    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        if (isModelReady()) return@withContext true
        if (isDownloadingModel) return@withContext false
        setDownloading(true)
        lastError = null
        try {
            modelDir.mkdirs()
            val totalBytes = REMOTE_FILES.sumOf { it.bytes }
            var downloadedBytes = 0L
            for (file in REMOTE_FILES) {
                if (!downloadFile(file.name, file.bytes) { chunk ->
                        onProgress((downloadedBytes + chunk).toFloat() / totalBytes)
                    }
                ) throw IllegalStateException("Failed to download ${file.name}")
                downloadedBytes += file.bytes
                onProgress(downloadedBytes.toFloat() / totalBytes)
            }
            isModelReady().also { ready -> if (!ready) lastError = "Model files missing after download." }
        } catch (t: Throwable) {
            android.util.Log.e("OmniVoice", "model download failed", t)
            lastError = "${t::class.simpleName}: ${t.message ?: "no detail"}"
            false
        } finally {
            setDownloading(false)
        }
        isModelReady()
    }

    private fun downloadFile(name: String, expectedBytes: Long, onChunk: (Long) -> Unit): Boolean {
        val target = File(modelDir, name)
        if (target.exists() && target.length() == expectedBytes) return true
        val partial = File(modelDir, "$name.part")
        val conn = URL("$BASE_URL/$name").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            conn.connect()
            val total = conn.contentLengthLong
            FileOutputStream(partial).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 17)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        onChunk(copied)
                    }
                    if (total > 0 && copied != total) throw IllegalStateException(
                        "$name incomplete: $copied of $total bytes"
                    )
                }
            }
        } finally {
            conn.disconnect()
        }
        return partial.renameTo(target)
    }
}
