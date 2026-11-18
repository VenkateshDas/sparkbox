package me.venkatesh.saveobsidian

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.*
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Transcribe an existing recording with Android's installed language model. */
internal object LocalSpeech {
    @JvmStatic fun read(file: File): String = runBlocking {
        withTimeout(120_000) {
            val options = speechRecognizerOptions {
                locale = Locale.getDefault()
                preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
            }
            SpeechRecognition.getClient(options).use { recognizer ->
                if (recognizer.checkStatus() != FeatureStatus.AVAILABLE)
                    throw IOException("On-device speech unavailable for ${options.locale}. Install the offline speech language or choose Remote. Recording kept.")
                val source = AudioSource.fromFile(file)
                try {
                    val text = StringBuilder()
                    recognizer.startRecognition(speechRecognizerRequest { audioSource = source }).collect { response ->
                        if (Tagger.halt()) throw IOException("Transcription paused")
                        when (response) {
                            is SpeechRecognizerResponse.FinalTextResponse -> text.append(response.text).append(' ')
                            is SpeechRecognizerResponse.ErrorResponse -> throw IOException("On-device transcription failed", response.e)
                            else -> Unit
                        }
                    }
                    text.toString().trim().ifEmpty { throw IOException("No speech recognized. Recording kept.") }
                } finally { source.close() }
            }
        }
    }
}
