package app.starrnote.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Renders small document previews without replaying page ink on the UI thread. */
final class PageThumbnail extends View {
    private static final ExecutorService RENDER = Executors.newSingleThreadExecutor();
    private final Paper.Images images;
    private final android.graphics.Rect destination = new android.graphics.Rect();
    private Notebook.Page page;
    private Bitmap background, preview;
    private volatile int generation;
    private boolean pending;

    PageThumbnail(Context context, Paper.Images images) {
        super(context);
        this.images = images;
    }

    void bind(Notebook.Page value, Bitmap image) {
        if (page != value) preview = null;
        page = value;
        background = image;
        reset();
    }

    private void reset() {
        generation++;
        pending = false;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        preview = null;
        reset();
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        destination.set(0, 0, getWidth(), getHeight());
        if (preview != null) canvas.drawBitmap(preview, null, destination, null);
        if (pending || page == null || getWidth() == 0 || getHeight() == 0) return;
        pending = true;
        int request = generation;
        int width = Math.min(240, getWidth()), height = Math.min(240, getHeight());
        Notebook.Page snapshot = page.snapshot();
        Bitmap image = background;
        RENDER.execute(() -> {
            if (request != generation) return;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                PageBrowser.drawPreview(new Canvas(bitmap), new Paper(), snapshot, image, images, width, height);
            } catch (RuntimeException failure) {
                android.util.Log.w("StarrNote", "Page thumbnail unavailable", failure);
                return;
            }
            post(() -> {
                if (request != generation || !isAttachedToWindow()) return;
                preview = bitmap;
                invalidate();
            });
        });
    }
}
