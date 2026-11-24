package app.starrnote.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class InkView extends View {
    interface Listener {
        void changed();
        void insert(String tool, float x, float y);
        void openLink(String link);
        void selectionChanged(int count);
        void zoomChanged(float zoom);
        void eraserFinished();
    }

    private final Notebook.Page page;
    private final Paper paper = new Paper();
    private final InfiniteRecording infiniteRecording = android.os.Build.VERSION.SDK_INT >= 29
            ? new InfiniteRecording() : null;
    private boolean paperChanged = true;
    private final Paper.Images images;
    private final Listener listener;
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayDeque<List<Notebook.Mark>> undo = new ArrayDeque<>();
    private final ArrayDeque<List<Notebook.Mark>> redo = new ArrayDeque<>();
    private final Set<Notebook.Mark> selected = new LinkedHashSet<>();
    private final Set<Notebook.Mark> erased = new HashSet<>();
    private final List<Notebook.Point> lasso = new ArrayList<>();
    private Bitmap background;
    interface PdfDetail {
        void render(android.graphics.RectF region, float scale, java.util.function.Consumer<Bitmap> result);
    }
    PdfDetail pdfDetail;
    private Bitmap detailBitmap;
    private android.graphics.RectF detailRegion;
    private android.graphics.RectF requestedRegion;
    private float detailScale, requestedScale;
    private boolean detailPending;
    private final Runnable renderDetail = this::renderPdfDetail;
    private Notebook.Mark draft;
    private String layer;
    private float zoom = 1;
    private float panX, panY, previousX, previousY, previousSpan;
    private float dragX, dragY;
    private int pointerId = -1;
    private boolean panning, moved, dragSelection, erasing;
    private float eraseStartX, eraseStartY, eraseLastX, eraseLastY, eraseRadius;
    int eraseMode, eraseFilter;
    float eraseSize = 16;
    boolean erasePressure;
    boolean stylusOnly, zoomLocked, scribble = true, straight;
    boolean charcoalWorkspace;
    String tool = "Pen";
    int inkColor = 0xff172f33;
    float width = 5;
    int inkOpacity = 100, stabilization;
    int pressureSensitivity = 100;

    InkView(Context context, Notebook.Page page, Paper.Images images, Listener listener) {
        super(context);
        this.page = page;
        this.images = images;
        this.listener = listener;
        layer = page.layers.get(0).id;
        setContentDescription("Writing canvas");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
    }

    void setBackgroundPage(Bitmap bitmap) {
        background = bitmap;
        invalidate();
    }

    void selectLayer(String id) { layer = id; }
    String activeLayer() { return layer; }

    record Viewport(String page, float zoom, float panX, float panY) {}

    Viewport viewport() { return new Viewport(page.id, zoom, panX, panY); }

    void restoreViewport(Viewport state) {
        if (!page.id.equals(state.page())) return;
        zoom = state.zoom();
        panX = state.panX();
        panY = state.panY();
        invalidate();
    }

    void selectTool(String tool) {
        this.tool = tool;
        clearSelection();
    }

    void fit() {
        boolean changedZoom = zoom != 1;
        zoom = 1;
        panX = panY = 0;
        invalidate();
        if (changedZoom) listener.zoomChanged(zoom);
    }

    void magnify() { zoomBy(1.5f); }

    void zoomBy(float factor) {
        zoomAround(factor, getWidth() * .5f, getHeight() * .5f);
        constrainPan();
        invalidate();
    }

    private void zoomAround(float factor, float x, float y) {
        float updated = Math.max(.25f, Math.min(12, zoom * factor));
        if (updated == zoom) return;
        float anchorX = localX(x), anchorY = localY(y);
        zoom = updated;
        panX += x - (originX() + anchorX * scale());
        panY += y - (originY() + anchorY * scale());
        listener.zoomChanged(zoom);
    }

    void extend() {
        if (page.infinite()) return;
        if (page.width < 9000) page.width = Math.min(9000, page.width + 840);
        if (page.height < 12000) page.height = Math.min(12000, page.height + 1120);
        listener.changed();
        invalidate();
    }

    private float fitScale() {
        int fitWidth = page.infinite() ? 840 : page.width;
        int fitHeight = page.infinite() ? 1120 : page.height;
        float margin = 20 * getResources().getDisplayMetrics().density;
        return Math.max(.015f, Math.min((getWidth() - margin) / fitWidth, (getHeight() - margin) / fitHeight));
    }

    private float scale() { return fitScale() * zoom; }
    private float originX() { return (getWidth() - (page.infinite() ? 840 : page.width) * scale()) / 2 + panX; }
    private float originY() { return (getHeight() - (page.infinite() ? 1120 : page.height) * scale()) / 2 + panY; }
    private float localX(float x) { return (x - originX()) / scale(); }
    private float localY(float y) { return (y - originY()) / scale(); }

    private android.graphics.RectF visiblePdfRegion(float margin) {
        return new android.graphics.RectF(Math.max(0, localX(-margin)), Math.max(0, localY(-margin)),
                Math.min(page.width, localX(getWidth() + margin)), Math.min(page.height, localY(getHeight() + margin)));
    }

    private void requestPdfDetail() {
        if (pdfDetail == null || background == null || detailPending) return;
        android.graphics.RectF view = visiblePdfRegion(0);
        float currentScale = scale();
        if (view.isEmpty() || currentScale <= 1.1f * background.getWidth() / page.width
                || (detailRegion != null && detailRegion.contains(view) && currentScale <= detailScale * 1.08f)) return;
        removeCallbacks(renderDetail);
        postDelayed(renderDetail, 85);
    }

    private void renderPdfDetail() {
        if (!isAttachedToWindow()) return;
        android.graphics.RectF region = visiblePdfRegion(64);
        if (region.isEmpty()) return;
        float resolution = Math.min(scale() * 1.2f, 2400 / Math.max(region.width(), region.height()));
        if (region.equals(requestedRegion) && resolution == requestedScale) return;
        requestedRegion = region;
        requestedScale = resolution;
        detailPending = true;
        pdfDetail.render(region, resolution, bitmap -> {
            detailPending = false;
            if (!isAttachedToWindow()) {
                if (bitmap != null) bitmap.recycle();
                return;
            }
            if (bitmap != null) {
                detailBitmap = bitmap;
                detailRegion = region;
                detailScale = resolution;
            }
            invalidate();
        });
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(renderDetail);
        if (android.os.Build.VERSION.SDK_INT >= 29 && infiniteRecording != null) infiniteRecording.discard();
        super.onDetachedFromWindow();
    }

    boolean isInteracting() { return pointerId != -1; }

    void invalidatePaper() {
        paperChanged = true;
        invalidate();
    }

    @android.annotation.TargetApi(29)
    private final class InfiniteRecording {
        private final android.graphics.RenderNode node = new android.graphics.RenderNode("Infinite page");
        private float left, top, zoom;
        private int width, height;

        void draw(Canvas canvas, float x, float y, float scale) {
            int viewWidth = getWidth(), viewHeight = getHeight();
            if (paperChanged || !node.hasDisplayList() || x != left || y != top || scale != zoom
                    || viewWidth != width || viewHeight != height) {
                node.setPosition(0, 0, viewWidth, viewHeight);
                Canvas recording = node.beginRecording(viewWidth, viewHeight);
                try {
                    recording.clipRect(0, 0, viewWidth, viewHeight);
                    recording.translate(x, y);
                    recording.scale(scale, scale);
                    paper.draw(recording, page, null, images, charcoalWorkspace);
                } finally {
                    node.endRecording();
                }
                left = x;
                top = y;
                zoom = scale;
                width = viewWidth;
                height = viewHeight;
                paperChanged = false;
            }
            canvas.drawRenderNode(node);
        }

        void discard() { node.discardDisplayList(); }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        requestPdfDetail();
        canvas.drawColor(charcoalWorkspace ? 0xff353535 : 0xffdce5e0);
        float x = originX(), y = originY(), scale = scale();
        boolean reusePaper = android.os.Build.VERSION.SDK_INT >= 29 && infiniteRecording != null
                && canvas.isHardwareAccelerated() && page.infinite()
                && getWidth() > 0 && getHeight() > 0;
        if (android.os.Build.VERSION.SDK_INT >= 29 && reusePaper) infiniteRecording.draw(canvas, x, y, scale);
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        if (!page.infinite()) {
            overlay.setColor(0x210d302b);
            overlay.setStyle(Paint.Style.FILL);
            canvas.drawRect(3, 7, page.width + 5, page.height + 7, overlay);
        }
        if (!reusePaper) paper.draw(canvas, page, background, images, charcoalWorkspace, detailBitmap, detailRegion);
        if (!page.infinite()) canvas.clipRect(0, 0, page.width, page.height);
        if (draft != null) paper.drawMark(canvas, draft, images);
        if (erasing && eraseMode == 2) {
            overlay.setStyle(Paint.Style.STROKE);
            overlay.setStrokeWidth(2 / scale());
            overlay.setColor(0xcc176961);
            canvas.drawCircle(eraseStartX, eraseStartY, eraseRadius, overlay);
        }
        canvas.save();
        canvas.translate(dragX, dragY);
        if (dragSelection && moved) {
            for (Notebook.Mark mark : selected) paper.drawMark(canvas, mark, images);
        }
        overlay.setColor(0xff05796e);
        overlay.setStrokeWidth(2 / scale());
        overlay.setStyle(Paint.Style.STROKE);
        if (!selected.isEmpty() || !lasso.isEmpty()) {
            overlay.setPathEffect(new DashPathEffect(new float[]{6 / scale, 4 / scale}, 0));
        }
        for (Notebook.Mark mark : selected) {
            RectF bounds = paper.bounds(mark);
            bounds.inset(-7, -7);
            canvas.drawRect(bounds, overlay);
        }
        canvas.restore();
        for (int i = 1; i < lasso.size(); i++) {
            Notebook.Point a = lasso.get(i - 1), b = lasso.get(i);
            canvas.drawLine(a.x(), a.y(), b.x(), b.y(), overlay);
        }
        overlay.setPathEffect(null);
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            int input = event.getToolType(0);
            boolean finger = input != MotionEvent.TOOL_TYPE_STYLUS && input != MotionEvent.TOOL_TYPE_ERASER;
            panning = tool.equals("Hand") || (stylusOnly && finger);
            pointerId = event.getPointerId(0);
            previousX = event.getX();
            previousY = event.getY();
            moved = false;
            previousSpan = 0;
            dragX = dragY = 0;
            if (!panning) begin(event);
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            int active = event.findPointerIndex(pointerId);
            if (active < 0 || event.getToolType(active) == MotionEvent.TOOL_TYPE_STYLUS) return true;
            if (!panning && !erased.isEmpty()) finishChange();
            resetGesture();
            pointerId = event.getPointerId(0);
            panning = true;
            previousSpan = span(event);
            previousX = (event.getX(0) + event.getX(1)) / 2;
            previousY = (event.getY(0) + event.getY(1)) / 2;
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            int active = event.findPointerIndex(pointerId);
            if (active < 0) return true;
            if (panning) pan(event, active);
            else {
                for (int i = 0; i < event.getHistorySize(); i++) {
                    update(localX(event.getHistoricalX(active, i)), localY(event.getHistoricalY(active, i)),
                            event.getHistoricalPressure(active, i));
                }
                update(localX(event.getX(active)), localY(event.getY(active)), event.getPressure(active));
            }
            invalidate();
            return true;
        }
        if ((action == MotionEvent.ACTION_POINTER_UP && event.getPointerId(index) == pointerId)
                || action == MotionEvent.ACTION_UP) {
            if (!panning) end(localX(event.getX(index)), localY(event.getY(index)), event.getPressure(index));
            resetGesture();
            if (action == MotionEvent.ACTION_POINTER_UP) {
                int next = index == 0 ? 1 : 0;
                pointerId = event.getPointerId(next);
                previousX = event.getX(next);
                previousY = event.getY(next);
                panning = true;
            } else {
                performClick();
            }
            invalidate();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            previousSpan = 0;
            int active = event.findPointerIndex(pointerId);
            if (active >= 0) {
                previousX = event.getX(active);
                previousY = event.getY(active);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            if (!erased.isEmpty()) finishChange();
            resetGesture();
            invalidate();
            return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void begin(MotionEvent event) {
        float x = localX(event.getX()), y = localY(event.getY());
        if (!insidePage(x, y)) return;
        if (tool.equals("Lasso")) {
            for (Notebook.Mark mark : selected) {
                if (paper.hit(mark, x, y, 24)) {
                    dragSelection = true;
                    return;
                }
            }
            clearSelection();
            lasso.add(new Notebook.Point(x, y, 1));
            return;
        }
        if (tool.equals("Read")) return;
        String actual = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ? "Eraser" : tool;
        if (actual.equals("Eraser")) {
            erasing = true;
            eraseStartX = eraseLastX = x;
            eraseStartY = eraseLastY = y;
            eraseRadius = radius(event.getPressure());
            if (eraseMode != 2) erase(x, y, eraseRadius);
            return;
        }
        Notebook.Layer active = page.layer(layer);
        if (active.locked || !active.visible) return;
        if (actual.equals("Text") || actual.equals("Link")) return;
        float thickness = actual.equals("Tape") ? Math.max(21, width * 4.5f) : width;
        draft = new Notebook.Mark(actual, active.id, inkColor, thickness);
        draft.opacity = inkOpacity;
        draft.pressureSensitivity = pressureSensitivity;
        draft.points.add(new Notebook.Point(x, y, event.getPressure()));
    }

    private void update(float x, float y, float pressure) {
        float dx = x - localX(previousX), dy = y - localY(previousY);
        if (Math.abs(dx) + Math.abs(dy) > 4 / scale()) moved = true;
        if (dragSelection) {
            dragX = dx;
            dragY = dy;
        } else if (!lasso.isEmpty()) {
            lasso.add(new Notebook.Point(x, y, pressure));
        } else if (draft != null) {
            Notebook.Point first = draft.points.get(0);
            if (straight && (Brushes.isHighlighter(draft.tool) || draft.tool.equals("Tape"))) {
                if (Math.abs(x - first.x()) >= Math.abs(y - first.y())) y = first.y();
                else x = first.x();
                if (draft.points.size() == 2) draft.points.remove(1);
            } else if (isShape(draft.tool) && draft.points.size() == 2) {
                draft.points.remove(1);
            } else if (Brushes.isInk(draft.tool) && stabilization > 0) {
                Notebook.Point last = draft.points.get(draft.points.size() - 1);
                float smoothing = stabilization * .007f;
                x += (last.x() - x) * smoothing;
                y += (last.y() - y) * smoothing;
            }
            draft.points.add(new Notebook.Point(x, y, pressure));
        } else if (erasing) {
            if (eraseMode == 2) {
                eraseRadius = Math.max(radius(pressure), (float) Math.hypot(x - eraseStartX, y - eraseStartY));
            } else {
                float radius = radius(pressure);
                int steps = Math.max(1, Math.min(64, (int) Math.ceil(Math.hypot(x - eraseLastX, y - eraseLastY) / radius)));
                for (int i = 1; i <= steps; i++) {
                    float t = i / (float) steps;
                    erase(eraseLastX + (x - eraseLastX) * t, eraseLastY + (y - eraseLastY) * t, radius);
                }
                eraseLastX = x;
                eraseLastY = y;
            }
        }
    }

    private static boolean isShape(String tool) {
        return switch (tool) {
            case "Line", "Rectangle", "Ellipse", "Arrow", "Table" -> true;
            default -> false;
        };
    }

    private void end(float x, float y, float pressure) {
        update(x, y, pressure);
        if (erasing && eraseMode == 2) erase(eraseStartX, eraseStartY, eraseRadius);
        if (dragSelection && moved) {
            snapshot();
            Set<Notebook.Mark> movedMarks = new LinkedHashSet<>();
            for (int i = 0; i < page.marks.size(); i++) {
                Notebook.Mark mark = page.marks.get(i);
                if (!selected.contains(mark)) continue;
                Notebook.Mark replacement = mark.copy();
                replacement.move(dragX, dragY);
                page.marks.set(i, replacement);
                movedMarks.add(replacement);
            }
            selected.clear();
            selected.addAll(movedMarks);
            finishChange();
        } else if (!lasso.isEmpty()) {
            select(x, y);
        } else if (draft != null) {
            if (Brushes.isInk(draft.tool) && stabilization > 0) draft.points.add(new Notebook.Point(x, y, pressure));
            if (!scribble || !Brushes.isPen(draft.tool) || !eraseScribble(draft)) {
                snapshot();
                page.marks.add(draft);
            }
            finishChange();
        } else if (!erased.isEmpty()) {
            finishChange();
            listener.eraserFinished();
        } else if (!moved && insidePage(x, y)) {
            if (tool.equals("Read")) {
                activate(x, y);
            } else if ((tool.equals("Text") || tool.equals("Link")) && !page.layer(layer).locked
                    && page.layer(layer).visible) {
                listener.insert(tool, x, y);
            }
        }
    }

    private boolean insidePage(float x, float y) {
        return page.infinite() || (x >= 0 && y >= 0 && x < page.width && y < page.height);
    }

    private void activate(float x, float y) {
        for (int i = page.marks.size() - 1; i >= 0; i--) {
            Notebook.Mark mark = page.marks.get(i);
            if (!page.layer(mark.layer).visible || !paper.hit(mark, x, y, 13)) continue;
            if (mark.tool.equals("Tape") && !page.layer(mark.layer).locked) {
                snapshot();
                Notebook.Mark replacement = mark.copy();
                replacement.revealed = !mark.revealed;
                page.marks.set(i, replacement);
                finishChange();
                return;
            }
            if (!mark.link.isEmpty()) {
                listener.openLink(mark.link);
                return;
            }
        }
    }

    private void resetGesture() {
        draft = null;
        lasso.clear();
        erased.clear();
        pointerId = -1;
        dragSelection = false;
        erasing = false;
        dragX = dragY = 0;
        previousSpan = 0;
    }

    private void pan(MotionEvent event, int active) {
        float x = event.getX(active), y = event.getY(active);
        if (event.getPointerCount() > 1) {
            x = (event.getX(0) + event.getX(1)) / 2;
            y = (event.getY(0) + event.getY(1)) / 2;
            float distance = span(event);
            if (!zoomLocked && previousSpan > 0 && distance > 0) {
                zoomAround(distance / previousSpan, previousX, previousY);
            }
            previousSpan = distance;
        }
        panX += x - previousX;
        panY += y - previousY;
        previousX = x;
        previousY = y;
        constrainPan();
    }

    private void constrainPan() {
        if (page.infinite()) return;
        float minimum = 42 * getResources().getDisplayMetrics().density;
        float left = originX();
        float top = originY();
        if (left > getWidth() - minimum) panX -= left - getWidth() + minimum;
        if (left + page.width * scale() < minimum) panX += minimum - left - page.width * scale();
        if (top > getHeight() - minimum) panY -= top - getHeight() + minimum;
        if (top + page.height * scale() < minimum) panY += minimum - top - page.height * scale();
    }

    private float span(MotionEvent event) {
        return (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
    }

    private boolean editable(Notebook.Mark mark) {
        Notebook.Layer config = page.layer(mark.layer);
        return config.visible && !config.locked;
    }

    private float radius(float pressure) {
        return Math.max(2 / scale(), eraseSize * (erasePressure ? .4f + .7f * Math.min(1.25f, pressure) : 1));
    }

    private boolean canErase(Notebook.Mark mark) {
        return editable(mark) && switch (eraseFilter) {
            case 1 -> Brushes.isHighlighter(mark.tool);
            case 2 -> mark.tool.equals("Tape");
            default -> true;
        };
    }

    private void erase(float x, float y, float radius) {
        for (int i = page.marks.size() - 1; i >= 0; i--) {
            Notebook.Mark mark = page.marks.get(i);
            if (!canErase(mark)) continue;
            List<Notebook.Mark> pieces = null;
            if (eraseMode == 1 && StrokeCuts.supports(mark)) {
                RectF bounds = paper.bounds(mark);
                float reach = radius + mark.thickness * .5f;
                bounds.inset(-reach, -reach);
                if (!bounds.contains(x, y)) continue;
                pieces = StrokeCuts.cut(mark, x, y, reach);
                if (pieces == null) continue;
            } else {
                if (eraseMode == 1 || !paper.hit(mark, x, y, radius + mark.thickness * .5f)) continue;
            }
            if (erased.isEmpty()) snapshot();
            erased.add(mark);
            page.marks.remove(i);
            if (eraseMode == 1) page.marks.addAll(i, pieces);
            paperChanged = true;
        }
    }

    private boolean eraseScribble(Notebook.Mark stroke) {
        if (stroke.points.size() < 12) return false;
        RectF region = paper.bounds(stroke);
        if (region.width() < 40 || region.height() < 9 || region.width() > region.height() * 8) return false;
        float anchor = stroke.points.get(0).x();
        int direction = 0, reversals = 0;
        for (Notebook.Point point : stroke.points) {
            float delta = point.x() - anchor;
            if (Math.abs(delta) < region.width() * .27f) continue;
            int next = delta > 0 ? 1 : -1;
            if (direction != 0 && direction != next) reversals++;
            anchor = point.x();
            direction = next;
        }
        if (reversals < 4) return false;
        List<Notebook.Mark> targets = new ArrayList<>();
        for (Notebook.Mark mark : page.marks) {
            if (!editable(mark)) continue;
            for (Notebook.Point point : stroke.points) {
                if (paper.hit(mark, point.x(), point.y(), 7)) {
                    targets.add(mark);
                    break;
                }
            }
        }
        if (targets.isEmpty()) return false;
        snapshot();
        page.marks.removeAll(targets);
        return true;
    }

    private void select(float x, float y) {
        for (Notebook.Mark mark : page.marks) {
            if (!editable(mark)) continue;
            RectF box = paper.bounds(mark);
            if (moved ? enclosed(box.centerX(), box.centerY()) : paper.hit(mark, x, y, 15 / scale())) {
                selected.add(mark);
            }
        }
        listener.selectionChanged(selected.size());
    }

    private boolean enclosed(float x, float y) {
        boolean inside = false;
        for (int i = 0, j = lasso.size() - 1; i < lasso.size(); j = i++) {
            Notebook.Point a = lasso.get(i), b = lasso.get(j);
            if ((a.y() > y) != (b.y() > y) && x < (b.x() - a.x()) * (y - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    List<Notebook.Mark> copySelection() { return copy(new ArrayList<>(selected)); }

    Notebook.Mark editableText() {
        if (selected.size() != 1) return null;
        Notebook.Mark mark = selected.iterator().next();
        return editable(mark) && (mark.tool.equals("Text") || mark.tool.equals("Link")) ? mark : null;
    }

    void editText(Notebook.Mark mark, String value, String address) {
        if (!selected.contains(mark) || !editable(mark)) return;
        snapshot();
        Notebook.Mark replacement = mark.copy();
        replacement.value = value;
        replacement.link = address;
        page.marks.set(page.marks.indexOf(mark), replacement);
        selected.remove(mark);
        selected.add(replacement);
        finishChange();
    }

    void paste(List<Notebook.Mark> marks) {
        Notebook.Layer active = page.layer(layer);
        if (marks.isEmpty() || active.locked || !active.visible) return;
        snapshot();
        clearSelection();
        for (Notebook.Mark mark : copy(marks)) {
            mark.layer = active.id;
            mark.move(22, 22);
            page.marks.add(mark);
        }
        finishChange();
    }

    void deleteSelection() {
        if (selected.isEmpty()) return;
        snapshot();
        page.marks.removeAll(selected);
        clearSelection();
        finishChange();
    }

    void clearLayer() {
        if (!page.layer(layer).visible || page.layer(layer).locked) return;
        snapshot();
        page.marks.removeIf(mark -> mark.layer.equals(layer));
        clearSelection();
        finishChange();
    }

    void insertImage(String file, float ratio) {
        float left = localX(0), top = localY(0), right = localX(getWidth()), bottom = localY(getHeight());
        if (!page.infinite()) {
            left = Math.max(0, left);
            top = Math.max(0, top);
            right = Math.min(page.width, right);
            bottom = Math.min(page.height, bottom);
        }
        float maxWidth = Math.min(410, (right - left) * .65f);
        float maxHeight = Math.min(570, (bottom - top) * .65f);
        float width = Math.min(maxWidth, maxHeight / ratio);
        float height = width * ratio;
        add("Image", file, "", (left + right - width) / 2, (top + bottom - height) / 2, width, height);
    }

    void frameInk() {
        if (!page.infinite()) { fit(); return; }
        RectF content = paper.printBounds(page);
        float ratio = Math.min((getWidth() - 24) / content.width(), (getHeight() - 24) / content.height());
        zoom = Math.max(.25f, Math.min(12, ratio / fitScale()));
        panX = panY = 0;
        panX = getWidth() * .5f - originX() - content.centerX() * scale();
        panY = getHeight() * .5f - originY() - content.centerY() * scale();
        listener.zoomChanged(zoom);
        invalidate();
    }

    void add(String tool, String value, String link, float x, float y, float size, float imageHeight) {
        add(tool, value, link, x, y, size, imageHeight, "sans-serif");
    }

    void add(String tool, String value, String link, float x, float y, float size, float imageHeight, String font) {
        Notebook.Layer active = page.layer(layer);
        if (active.locked || !active.visible) return;
        snapshot();
        Notebook.Mark mark = new Notebook.Mark(tool, active.id, inkColor, size);
        mark.value = value;
        mark.link = link;
        mark.font = font;
        mark.points.add(new Notebook.Point(x, y, 1));
        if (tool.equals("Image")) mark.points.add(new Notebook.Point(x + size, y + imageHeight, 1));
        page.marks.add(mark);
        finishChange();
    }

    void undo() { step(undo, redo); }
    void redo() { step(redo, undo); }

    private void step(ArrayDeque<List<Notebook.Mark>> from, ArrayDeque<List<Notebook.Mark>> to) {
        if (from.isEmpty()) return;
        to.push(new ArrayList<>(page.marks));
        page.marks.clear();
        page.marks.addAll(from.pop());
        clearSelection();
        finishChange();
    }

    private void snapshot() {
        // Committed marks are replaced when edited, so undo history can share them.
        undo.push(new ArrayList<>(page.marks));
        if (undo.size() > 30) undo.removeLast();
        redo.clear();
    }

    private List<Notebook.Mark> copy(List<Notebook.Mark> marks) {
        List<Notebook.Mark> result = new ArrayList<>(marks.size());
        for (Notebook.Mark mark : marks) result.add(mark.copy());
        return result;
    }

    void clearSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        listener.selectionChanged(0);
        invalidate();
    }

    private void finishChange() {
        listener.changed();
        invalidate();
    }
}
