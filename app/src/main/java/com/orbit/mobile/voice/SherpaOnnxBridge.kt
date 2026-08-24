package com.orbit.mobile.voice

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

object SherpaOnnxBridge {
    fun transcribe(modelDirPath: String, samples: ShortArray, sampleRate: Int): String {
        val floats = FloatArray(samples.size) { samples[it] / 32768f }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$modelDirPath/encoder.int8.onnx",
                    decoder = "$modelDirPath/decoder.int8.onnx",
                    joiner = "$modelDirPath/joiner.int8.onnx",
                ),
                tokens = "$modelDirPath/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = 2,
            ),
        )
        val recognizer = OfflineRecognizer(assetManager = null, config = config)
        val stream = recognizer.createStream()
        stream.acceptWaveform(floats, sampleRate)
        recognizer.decode(stream)
        val text = recognizer.getResult(stream).text
        stream.release()
        recognizer.release()
        return text.trim()
    }
}
