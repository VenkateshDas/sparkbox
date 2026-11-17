# Scout retrieval: latency research and implementation

Researched and implemented 2026-11-17. Scope: the Android Scout harness, about 1,000 notes, existing OpenAI `text-embedding-3-large` vectors at 1,024 dimensions. Preserve the current vector space and the vault as the source of truth.

The important distinction is between embedding the query, finding matching vectors, reading evidence, and asking the model again. A faster nearest-neighbor algorithm only affects the second part. Query network calls and unnecessary model turns can remain even if vector scoring is instantaneous.

## What the sources support

| Technique | Evidence and tradeoff | Decision for Sparkbox |
|---|---|---|
| Exact vector scoring in memory | [Sentence Transformers](https://sbert.net/examples/sentence_transformer/applications/semantic-search/README.html) documents manual similarity search for small corpora, up to roughly a million entries. Its threshold is guidance, not a performance guarantee for phones. | Keep exact scoring for this vault. Remove disk reloads before adopting an approximate index. |
| Keyword + semantic rank fusion | [Elastic's hybrid-search documentation](https://www.elastic.co/docs/solutions/search/hybrid-search) recommends reciprocal rank fusion, which combines positions in ranked lists without pretending lexical and cosine scores have the same scale. | Scout now fuses up to 24 lexical and 24 semantic candidates, then returns at most eight note listings. Equal fusion scores have a stable path tie-break. |
| Exact embedding caching | [RedisVL's embedding-cache documentation](https://redis.io/docs/latest/develop/ai/redisvl/concepts/extensions/) distinguishes exact embedding reuse from semantically similar cached answers. | Cache successful query vectors only. Never reuse an answer or source excerpt because another question sounds similar. No Redis service or dependency is needed for an in-process cache. |
| SQLite FTS5/BM25 | [SQLite's documentation](https://www.sqlite.org/fts5.html) provides an inverted full-text index and BM25 ranking with column weights. This would primarily improve the lexical branch and its ranking, rather than remove query-embedding network time. | Keep the existing rare-term/title-weighted lexical ranker for this change. Evaluate FTS against labeled queries before introducing a second persistent document index and its update protocol. This implementation does not claim to be BM25. |
| Approximate nearest neighbors | [Faiss's selection guide](https://github.com/facebookresearch/faiss/wiki/guidelines-to-choose-an-index) discusses index-building cost, RAM, and search/index tradeoffs. | Defer HNSW or another approximate index until measured vector scoring becomes material as the corpus grows. Avoid an extra native dependency and approximate-recall tradeoff at the current size. |
| Smaller dimensions or a different encoder | [The embedding API](https://developers.openai.com/api/reference/resources/embeddings/methods/create) supports dimension reduction for compatible models. [Sentence Transformers](https://sbert.net/examples/sentence_transformer/applications/semantic-search/README.html) explains that queries and documents must inhabit the same embedding space. | Do not silently change the query encoder or dimensions. That needs a compatible rebuilt corpus index and retrieval-quality evaluation. |
| On-device embedding | [ONNX Runtime's mobile guide](https://onnxruntime.ai/docs/tutorials/mobile/) supports Android deployment and explicitly makes performance dependent on the model and device. | A promising way to remove cold-query network latency, but requires a bundled encoder/tokenizer, a corresponding corpus reindex, and actual device measurements. No phone is connected in this session. |

These are engineering choices inferred from the sources and measured code path. The sources do not establish that this particular app's retrieval quality improved.

## Changes shipped

**Query vectors.** A memory-only LRU holds up to 128 successful vectors, keyed by encoder/dimensions and exact query text with outer whitespace trimmed. Case and internal whitespace remain significant. Returned arrays are copies. Failed, zero, non-finite, dimension-mismatched, or cancelled embeddings are not admitted. Concurrent identical queries share in-flight work only within the same stop token, so stopping one chat cannot cancel another chat's lookup. Different queries do not wait for a cache-wide network lock.

**Document vectors.** Up to four index-file snapshots remain resident. File modification/size changes refresh a snapshot, and every successful `Index.save()` invalidates its cached snapshot even when timestamp and size are unchanged. Writers still use `Index.load()` and cannot accidentally edit the resident snapshot through the writer path. Vector caches remain rebuildable, app-private artifacts.

**Hybrid retrieval.** Scout uses rank fusion for both short and long queries, replacing its old short-query policy of appending semantic results behind the lexical list. Inbox captures also use the shared fusion function, with up to 24 candidates per branch and at most six returned captures. Existing Feed/Vault blend policy is retained. When semantic retrieval is unavailable, Scout explicitly reports lexical-only coverage. A partial or missing vector index does not remove lexical candidates.

**Evidence in the search call.** Search opens the best two note matches by default, bounded to three through the optional `read` argument. Each excerpt is at most 1,600 characters. `read: 0` returns listings only. The provider is read again for these excerpts, and the read cache is refreshed. Each successful excerpt carries a host evidence ID, exact character range, full-note hash, and honest full/excerpt coverage. Metadata listings receive no evidence IDs. Failure to open one match does not discard other matches. This can eliminate the separate search/read model round trip when the excerpts suffice; it does not force an answer from incomplete evidence.

These extend the earlier fixes: known document IDs avoid repeated directory traversal, concurrent greps share cold reads, query HTTP has one short attempt with keyword fallback, tool calls inherit the run's Stop token, and individual tool durations are stored with results.

## Validation and limits

Run:

```sh
./gradlew captureTest askStateTest lintDebug assembleDebug
./gradlew retrievalBenchmark
```

The new deterministic checks cover embedding reuse and eviction, encoder isolation, failed/invalid inputs, unrelated-query concurrency, cancellation isolation, index invalidation, lexical-only fallback, semantic-only candidates, fusion ordering/ties, citation identity/hash/coverage, updated source text, and metadata-only searches.

The benchmark uses 1,000 synthetic 1,024-dimensional vectors and 30 warm searches on the development machine. One measured run: **11.56 ms/search with index reloads versus 1.08 ms/search with a resident index**, with identical scores. It includes index access, text hashing and dot products. It excludes network embedding latency, Android storage, evidence reads, model inference, and end-to-end answer latency. It is not a phone speedup claim.

The ranking fixtures verify invariants, not real-world Recall@k or nDCG. The next useful evaluation is a labeled set of exact-title, identifier, synonym, multi-concept, and no-answer queries from this vault, scored before and after each encoder, ranking, or chunking change. Record warm/cold p50 and p95 for query encoding, local ranking, source reads, and total answer latency on the phone. Fresh queries still require the current remote encoder. Repeated queries avoid that network request; resident scoring and fewer model turns help independently.
