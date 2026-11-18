# Local model processing (removed)

Sparkbox 1.11.0 shipped optional on-device Gemma 4 E2B (LiteRT-LM) processing: a separate worker process, local text, vision and recall, offline speech and keyword Scout. It was removed to be redesigned. Recover it from the `fix/pdf-capture-ingest` history at `826efc9`.

What remains: PDF text extraction and OCR on the phone (`PdfCapture`, `PdfRenderService`, bundled ML Kit Latin OCR). Extracted text goes to OpenRouter models.

The original audit is in [2026-11-18-pdf-phone-validation.md](2026-11-18-pdf-phone-validation.md). Findings worth keeping for the redesign: loading the 2.6 GB model per call is costly, so keep one warm worker with an idle timeout; route every completion through one facade; Gemma needs no minimum Android version, only the isolated PDF renderer needs Android 15.
