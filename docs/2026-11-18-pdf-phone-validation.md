# On-device PDF validation

Sparkbox 1.10.2 (17), signed and minified, on a OnePlus 15R running Android 17. The owner shared a fresh copy of the 26-page [TokenEconomizer paper](https://arxiv.org/abs/2610.22732). Sparkbox filed the Source note, preserved the original attachment reference and generated one recall card. The background job ran for about 2 minutes 33 seconds.

The earlier release crashed because R8 removed two getters that LiteRT-LM 0.18.1 looks up through JNI. Build 1.10.2 preserves those methods and clears retry delays associated with the old worker crash.

## Data handling

| Stage | Execution and data |
|---|---|
| PDF extraction | Android's `PdfRenderer` reads the text layer and renders selected pages on the phone. |
| Note generation | Gemma 4 E2B through LiteRT-LM runs on the CPU. It generates figure descriptions, summary, claims, topic suggestions, lane and recall card. Sparkbox writes the results locally. The heading `Claude's Extraction` is fixed template text. |
| Background search indexing | With an OpenRouter key, Sparkbox sends new or changed note digests of up to 2,000 characters to `openai/text-embedding-3-large` through OpenRouter. This paper's digest includes the title, topics and opening raw text. Code inspection confirmed the payload construction. The retained device logs do not establish whether the embedding request for this note completed. |

The local ingest path keeps the PDF bytes, rendered images and Gemma prompts on the phone. Search indexing is a separate path. Ordinary text captures still use OpenRouter for AI enrichment in this build.

## Output quality

- **Text.** All 26 page sections were present. A per-page comparison against an independent Poppler extraction matched 98.1% of normalized word tokens by count. This metric ignores reading order. Multi-column pages and equations still had layout artifacts. Page 6 had the lowest overlap at about 71%.
- **Summary and claims.** The three claims and the recall card matched the paper's reported mechanisms and results, including the 18.4–30.9% cost reduction with comparable task success. The app's length cap cut off the displayed summary mid-sentence.
- **Figures.** Gemma described three of 12 candidate pages. The page 2 description confused the agent-turn schematic with the scatter plot axes. The page 3 description confused repositories with strategies. Two descriptions ended mid-thought.

## Energy estimate

The phone charged over USB during the run. Comparing the battery charge-counter rise during processing with the nearby baseline gave a rough whole-phone estimate of **0.09 Wh** for one PDF. The **0.06–0.13 Wh** planning range reflects the uncertainty in that comparison. This was a single run without an external power meter or an isolated app energy measurement. Percentages use the OnePlus 15R's typical 27.98 Wh battery capacity. [OnePlus specifications](https://www.oneplus.com/us/15r/specs)

| Workload | Serial processing time | Estimated energy | Battery equivalent |
|---|---:|---:|---:|
| 1 similar PDF | 2 min 33 sec | 0.06–0.13 Wh | 0.2–0.5% |
| 10 similar PDFs | ~25 min | 0.6–1.3 Wh | 2–5% |
| 40 similar PDFs | ~1 hr 42 min | 2.4–5.2 Wh | 9–19% |
| 40 short notes, hypothetical local inference | 10–20 min | ~0.24–1.02 Wh | ~1–4% |

The PDF rows assume similar page counts, figure samples and per-capture processing time. The short-note row assumes 15–30 seconds of local inference per note at the same estimated average power. Short-note inference was not benchmarked.
