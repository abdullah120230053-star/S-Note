package app.starrnote.android;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;

/** Lazily indexes strokes on this device when Smart Search is enabled. */
final class HandwritingIndex {
    private record Entry(List<Notebook.Mark> marks, int language, String text) {}
    private record Batch(Notebook.Page page, List<Notebook.Mark> marks, int language) {}
    private final WeakHashMap<Notebook.Page, Entry> entries = new WeakHashMap<>();
    private final ArrayDeque<Batch> queue = new ArrayDeque<>();
    private Batch active;
    private int failed;

    boolean matches(Notebook.Page page, String term, int language) {
        Entry entry = entries.get(page);
        return entry != null && entry.language == language && entry.text.contains(term)
                && entry.marks.equals(page.marks);
    }

    void scan(List<Notebook> notebooks, int language, Executor worker, Runnable progress) {
        queue.clear();
        failed = 0;
        for (Notebook note : notebooks) {
            if (note.trashed || note.deviceLock) continue;
            for (Notebook.Page page : note.pages) {
                Entry entry = entries.get(page);
                if (page.marks.isEmpty() || (entry != null && entry.language == language
                        && entry.marks.equals(page.marks))) continue;
                if (active != null && active.page == page && active.language == language
                        && active.marks.equals(page.marks)) continue;
                queue.add(new Batch(page, new ArrayList<>(page.marks), language));
            }
        }
        advance(worker, progress);
    }

    String status() {
        int count = queue.size() + (active == null ? 0 : 1);
        if (count > 0) return "Indexing handwriting · " + count + " pages left";
        return failed > 0 ? "Handwriting indexing incomplete · search again to retry" : "Smart Search · On";
    }

    void clear() { queue.clear(); }

    private void advance(Executor worker, Runnable progress) {
        progress.run();
        if (active != null || queue.isEmpty()) return;
        Batch batch = queue.removeFirst();
        active = batch;
        worker.execute(() -> Recognition.handwriting(batch.marks, batch.language,
                java.util.concurrent.ForkJoinPool.commonPool()).addOnCompleteListener(result -> {
            if (result.isSuccessful() && batch.marks.equals(batch.page.marks)) {
                entries.put(batch.page, new Entry(batch.marks, batch.language, result.getResult().toLowerCase(Locale.ROOT)));
            } else if (!result.isSuccessful()) {
                failed++;
            }
            active = null;
            advance(worker, progress);
        }));
    }
}
