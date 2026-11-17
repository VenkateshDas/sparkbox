package me.venkatesh.saveobsidian;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Bounded, memory-only embedding cache. It never caches answers or search results. */
public final class QueryVectors {
    private final int capacity, dimensions;
    private final Map<String, float[]> completed = new LinkedHashMap<>(16, 0.75f, true);
    // Only callers with the same stop token share in-flight work; cancelling one chat cannot cancel another.
    private final Map<Cancel, Map<String, FutureTask<float[]>>> pending = new HashMap<>();

    public QueryVectors(int capacity, int dimensions) {
        if (capacity < 1 || dimensions < 1) throw new IllegalArgumentException("Invalid cache size");
        this.capacity = capacity; this.dimensions = dimensions;
    }

    public float[] get(String namespace, String query, Cancel cancel, Callable<float[]> load) throws Exception {
        if (cancel.stopped()) throw new IOException("Operation aborted");
        if (query.trim().isEmpty()) throw new IllegalArgumentException("Empty search query");
        String key = namespace + "\0" + query.trim();
        FutureTask<float[]> task;
        boolean owner;
        synchronized (this) {
            float[] found = completed.get(key);
            if (found != null) return found.clone();
            Map<String, FutureTask<float[]>> group = pending.computeIfAbsent(cancel, ignored -> new HashMap<>());
            task = group.get(key);
            owner = task == null;
            if (owner) {
                task = new FutureTask<>(() -> {
                    float[] vector = load.call();
                    if (vector == null || vector.length != dimensions) throw new IOException("Invalid query vector dimensions");
                    double norm = 0;
                    for (float value : vector) {
                        if (!Float.isFinite(value)) throw new IOException("Invalid query vector values");
                        norm += (double) value * value;
                    }
                    if (norm == 0) throw new IOException("Empty query vector");
                    if (cancel.stopped()) throw new IOException("Operation aborted");
                    float[] result = Index.normalized(vector);
                    synchronized (this) {
                        completed.put(key, result);
                        if (completed.size() > capacity) completed.remove(completed.keySet().iterator().next());
                    }
                    return result;
                });
                group.put(key, task);
            }
        }
        try {
            if (owner) task.run(); // No network call holds the cache lock.
            float[] result = task.get();
            if (cancel.stopped()) throw new IOException("Operation aborted");
            return result.clone();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause();
            throw new IOException("Query embedding failed", failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw interrupted;
        } finally {
            if (owner) synchronized (this) {
                Map<String, FutureTask<float[]>> group = pending.get(cancel);
                group.remove(key);
                if (group.isEmpty()) pending.remove(cancel);
            }
        }
    }
}
