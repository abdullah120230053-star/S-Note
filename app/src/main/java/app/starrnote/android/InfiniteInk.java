package app.starrnote.android;

import android.graphics.Rect;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Locates committed ink near an Infinite canvas viewport, in the original drawing order. */
final class InfiniteInk {
    private static final int CELL_SIZE = 512;
    private static final int MAX_MARK_CELLS = 48;
    private final Notebook.Page page;
    private final Paper paper;
    private final Map<Long, List<Integer>> cells = new HashMap<>();
    private final List<Integer> shared = new ArrayList<>();
    private final TreeSet<Integer> visible = new TreeSet<>();
    private final List<Notebook.Mark> result = new ArrayList<>();
    private int indexed = -1;

    InfiniteInk(Notebook.Page page, Paper paper) {
        this.page = page;
        this.paper = paper;
    }

    void reset() { indexed = -1; }

    void append(Notebook.Mark mark) {
        if (indexed != page.marks.size() - 1) return;
        add(mark, indexed++);
    }

    List<Notebook.Mark> visible(Rect clip) {
        int left = cell(clip.left), top = cell(clip.top), right = cell(clip.right), bottom = cell(clip.bottom);
        if (cellCount(left, top, right, bottom) > 1536) return page.marks;
        if (indexed != page.marks.size()) {
            cells.clear();
            shared.clear();
            for (int i = 0; i < page.marks.size(); i++) add(page.marks.get(i), i);
            indexed = page.marks.size();
        }
        visible.clear();
        visible.addAll(shared);
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                List<Integer> entries = cells.get(key(x, y));
                if (entries != null) visible.addAll(entries);
            }
        }
        result.clear();
        for (int index : visible) result.add(page.marks.get(index));
        return result;
    }

    private void add(Notebook.Mark mark, int index) {
        if (mark.points.isEmpty()) return;
        if (!Brushes.isInk(mark.tool) && !Brushes.isMarker(mark.tool)) {
            shared.add(index);
            return;
        }
        RectF bounds = paper.bounds(mark);
        float padding = 2 * mark.thickness;
        int left = cell(bounds.left - padding), top = cell(bounds.top - padding);
        int right = cell(bounds.right + padding), bottom = cell(bounds.bottom + padding);
        if (cellCount(left, top, right, bottom) > MAX_MARK_CELLS) {
            shared.add(index);
            return;
        }
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) cells.computeIfAbsent(key(x, y), unused -> new ArrayList<>()).add(index);
        }
    }

    private static long key(int x, int y) { return ((long) x << 32) | (y & 0xffffffffL); }

    private static int cell(float value) { return (int) Math.floor(value / CELL_SIZE); }

    private static long cellCount(int left, int top, int right, int bottom) {
        return ((long) right - left + 1) * ((long) bottom - top + 1);
    }
}
