# Local and remote processing

The original OnePlus run already established that Gemma 4 E2B could turn a paper into a note and card. The main failures were missing pages in scans, erroneous interpretations of figures, a truncated summary and independent indexing calls that could send extracted text to OpenRouter. This change keeps the existing capture queue, renderer, plan checkpoint, vault writer and remote providers.

| Work | Local choice | Remote choice |
| --- | --- | --- |
| PDF text and scans | Isolated PdfRenderer. Read the text layer first. Run bundled Latin ML Kit OCR only on pages without text. Older platforms use OCR throughout. | Same extraction. Send text to the selected ingest model. |
| Summaries, classification, links, topics, cards, direct titles, recall feedback | Gemma 4 E2B in a separate process. A single ingest answer supplies classification and initial cards. | Existing OpenRouter model choices. |
| Photos, figures, video | Gemma vision. Optional PDF figures. Videos use at most three timestamped frames and explicitly label missing audio. | Existing vision, video and transcription models. |
| Voice recordings | ML Kit Basic with an available offline language model. Retain the recording on recognition failure. | Whisper through OpenRouter. |
| Search and Scout | Keyword retrieval. Local Scout answers from up to three matching note excerpts. | Remote embeddings and full Scout tools, edits and research. |

Local mode blocks new OpenRouter requests at the transport, including requests started with a previously retrieved key. Existing network transfers may finish. Choosing a different provider preserves the stored key. Selecting a web source still requests the source content.

## Runtime choices

Gemma already has an Android LiteRT-LM build and an established device result. The app uses two CPU threads, a 4,096-token context and bounded output. It admits one local card worker per batch, releases the inference process after the last client, and defers new generation in battery saver or severe thermal conditions. Figures default off in explicit local mode. [Gemma model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)

ML Kit ships an Android OCR runtime with a bundled recognizer. Page-at-a-time OCR avoids generative decoding and works offline on first use. LightOnOCR-2-1B targets harder layouts, equations and tables. LightOn reports throughput on an H100. Its public model card does not establish Android memory, latency or energy. Compare it against ML Kit on representative phone scans before adding another model download. [ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android) · [LightOnOCR model card](https://huggingface.co/lightonai/LightOnOCR-2-1B)

Speech uses a separate installed recognizer so a recording can be processed without loading Gemma. Language and device support vary. Semantic retrieval could use a quantized EmbeddingGemma as an optional second download. That implementation needs a model-specific vault index, matching query vectors and retrieval thresholds validated on actual notes. [ML Kit speech](https://developers.google.com/ml-kit/genai/speech-recognition/android) · [EmbeddingGemma](https://huggingface.co/google/embeddinggemma-300m)

## Validation

The instrumentation suites `pdf`, `pdf-worker`, `local-text` and `local-plan` exercise mixed documents, worker teardown, a real text completion and a structured extraction with a known numerical result. Run `./gradlew check assembleRelease` for the JVM checks, lint and minified APK. Compare charging and unplugged runs separately. For energy comparisons, use the same PDF, display state and model-cache state, and record elapsed time, peak process memory and battery charge-counter change across repeated runs. Emulator timings cannot establish battery consumption.

### OnePlus 15R, Android 17, November 18

The connected phone passed the six-page mixed PDF fixture (two native text pages and four raster pages), the stale-key transport guard, worker teardown, a real Gemma text completion and a structured local plan. The plan preserved the seeded 25 percent irrigation result. A separate read of the 26-page TokenEconomizer PDF returned 95,902 characters and reached page 26. The highest observed worker PSS was 1,451,302 KiB across 15 samples. This sampled value measures the worker's proportional share of memory. Whole-app consumption may be higher.

Basic speech returned “Acquainted in mango tree in the” for “I planted a mango tree in the garden.” The file pipeline worked. The transcript needs review. The signed, minified 1.11.0 (18) build then answered “mango” through the inference service. The Android 15 emulator passed the mixed PDF, worker and Gemma checks in airplane mode. These tests did not file a note into the owner's vault, make a paid provider call, measure video quality or measure battery consumption.
