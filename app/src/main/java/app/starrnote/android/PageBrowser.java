package app.starrnote.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

final class PageBrowser {
    private final MainActivity host;
    private final StudioStyle ui;
    private final Notebook note;
    private final Paper.Images images;
    private final LruCache<String, Bitmap> backgrounds = new LruCache<>(24);
    private final Set<String> pending = new HashSet<>();
    private final Set<String> unavailable = new HashSet<>();
    private ListView rail;
    private AbsListView dialog;

    PageBrowser(MainActivity host, Notebook note, Paper.Images images) {
        this.host = host;
        this.ui = host.ui;
        this.note = note;
        this.images = images;
    }

    ListView rail(IntConsumer open) {
        rail = list(open);
        return rail;
    }

    void openDialog(IntConsumer open) {
        android.app.AlertDialog chooser = new android.app.AlertDialog.Builder(host)
                .setTitle("Page previews").setNegativeButton("Close", null).create();
        dialog = list(position -> {
            chooser.dismiss();
            open.accept(position);
        });
        chooser.setView(dialog);
        chooser.setOnDismissListener(d -> dialog = null);
        chooser.show();
    }

    void openGrid(IntConsumer open) {
        android.app.AlertDialog chooser = new android.app.AlertDialog.Builder(host)
                .setTitle("Document grid").setNegativeButton("Close", null).create();
        GridView grid = new GridView(host);
        grid.setNumColumns(host.getResources().getConfiguration().screenWidthDp >= 650 ? 4 : 2);
        grid.setHorizontalSpacing(ui.dp(6));
        grid.setVerticalSpacing(ui.dp(6));
        ui.pad(grid, 10, 7, 10, 7);
        grid.setAdapter(new PagesAdapter());
        grid.setOnItemClickListener((parent, view, position, id) -> {
            chooser.dismiss();
            open.accept(position);
        });
        dialog = grid;
        chooser.setView(grid);
        chooser.setOnDismissListener(d -> dialog = null);
        chooser.show();
    }

    private ListView list(IntConsumer open) {
        ListView list = new ListView(host);
        list.setDivider(null);
        list.setClipToPadding(false);
        ui.pad(list, 6, 4, 6, 4);
        list.setAdapter(new PagesAdapter());
        list.setOnItemClickListener((parent, view, position, id) -> open.accept(position));
        list.setSelection(note.currentPage);
        return list;
    }

    void refresh(boolean showCurrent) {
        if (rail != null && rail.isShown()) {
            ((BaseAdapter) rail.getAdapter()).notifyDataSetChanged();
            if (showCurrent && (note.currentPage < rail.getFirstVisiblePosition()
                    || note.currentPage > rail.getLastVisiblePosition())) rail.setSelection(note.currentPage);
        }
        if (dialog != null) ((BaseAdapter) dialog.getAdapter()).notifyDataSetChanged();
    }

    private void requestBackground(Notebook.Page page) {
        if (page.background.isEmpty() || backgrounds.get(page.id) != null || unavailable.contains(page.id)
                || !pending.add(page.id)) return;
        host.readBackground(page, 260, bitmap -> {
            pending.remove(page.id);
            if (bitmap == null) unavailable.add(page.id);
            else backgrounds.put(page.id, bitmap);
            refresh(false);
        }, false);
    }

    private final class PagesAdapter extends BaseAdapter {
        @Override public int getCount() { return note.pages.size(); }
        @Override public Notebook.Page getItem(int position) { return note.pages.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Cell cell = recycled instanceof Cell previous ? previous : new Cell();
            Notebook.Page page = getItem(position);
            boolean current = note.currentPage == position;
            cell.setBackground(ui.ripple(current ? ui.lime : ui.surface, 12, ui.line));
            cell.label.setText("Page " + (position + 1));
            String description = page.title.isEmpty() ? page.pdfIndex >= 0 ? "PDF" : page.paper : page.title;
            cell.detail.setText(description);
            cell.setContentDescription("Page " + (position + 1) + ", " + description + (current ? ", current page" : ""));
            cell.preview.bind(page);
            requestBackground(page);
            return cell;
        }
    }

    private final class Cell extends LinearLayout {
        final Preview preview;
        final TextView label;
        final TextView detail;

        Cell() {
            super(host);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            ui.pad(this, 5, 6, 5, 6);
            preview = new Preview();
            preview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(preview, new LayoutParams(-1, ui.dp(91)));
            label = ui.text("", 13, ui.ink);
            label.setGravity(Gravity.CENTER);
            addView(label, new LayoutParams(-1, ui.dp(22)));
            detail = ui.text("", 11, ui.muted);
            detail.setGravity(Gravity.CENTER);
            detail.setSingleLine();
            detail.setEllipsize(TextUtils.TruncateAt.END);
            addView(detail);
        }
    }

    private final class Preview extends View {
        private final Paper paper = new Paper();
        private Notebook.Page page;

        Preview() { super(host); }

        void bind(Notebook.Page value) {
            page = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (page != null) drawPreview(canvas, paper, page, backgrounds.get(page.id), images, getWidth(), getHeight());
        }
    }

    static void drawPreview(Canvas canvas, Paper paper, Notebook.Page page, Bitmap background,
                            Paper.Images images, int viewWidth, int viewHeight) {
        android.graphics.RectF bounds = paper.printBounds(page);
        float width = bounds.width(), height = bounds.height();
        float scale = Math.min(viewWidth / width, viewHeight / height);
        canvas.save();
        canvas.translate((viewWidth - width * scale) / 2, (viewHeight - height * scale) / 2);
        canvas.scale(scale, scale);
        canvas.clipRect(0, 0, width, height);
        canvas.translate(-bounds.left, -bounds.top);
        paper.draw(canvas, page, background, images, false);
        canvas.restore();
    }
}
