package app.starrnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class PlayBooksActivity extends Activity {
    private static final int OPEN_BOOK = 1;
    private static final int PAPER = 0xfffffcf3;
    private static final Pattern HIDDEN = Pattern.compile("(?is)<(head|script|style)\\b[^>]*>.*?</\\1\\s*>");
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private SharedPreferences books;
    private File folder;
    private StudioStyle ui;
    private TextView title, status, mode, previous, next;
    private SheetView sheet;
    private String bookId;
    private BookPages loaded;
    private boolean spread, square, turning, busy, importing;
    private float pendingTurn;
    private volatile int generation;
    private int page;
    private float textSize;

    private record BookPages(String title, boolean rtl, int chapter, int chapters, StaticLayout layout,
                             int[] breaks, int width, int height) {
        int count() { return breaks.length - 1; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new StudioStyle(this, StudioStyle.CHARCOAL);
        books = getSharedPreferences("play_books", MODE_PRIVATE);
        folder = new File(getFilesDir(), "books");
        spread = books.getBoolean("spread", false);
        square = books.getBoolean("square", false);
        textSize = books.getFloat("textSize", 18);
        bookId = state == null ? books.getString("last", null) : state.getString("book");

        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.page);
        LinearLayout header = ui.row();
        ui.pad(header, 10, 5, 10, 5);
        ui.chip(header, ui.button("Library", false, this::finish));
        title = ui.text("Play Books", 18, ui.ink);
        title.setSingleLine();
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ui.chip(header, ui.button("Books", false, this::shelf));
        header.addView(ui.button("+ EPUB", true, this::pickBook));
        root.addView(header);

        HorizontalScrollView rail = new HorizontalScrollView(this);
        rail.setHorizontalScrollBarEnabled(false);
        LinearLayout controls = ui.row();
        ui.pad(controls, 10, 0, 10, 6);
        mode = ui.button("", false, () -> {
            spread = !spread;
            books.edit().putBoolean("spread", spread).apply();
            refreshMode();
            reflow();
        });
        ui.chip(controls, mode);
        ui.chip(controls, ui.button("A−", false, () -> resizeText(-2)));
        ui.chip(controls, ui.button("A+", false, () -> resizeText(2)));
        ui.chip(controls, ui.button("Page shape", false, this::pageShape));
        ui.chip(controls, ui.button("Chapters", false, this::chapters));
        refreshMode();
        rail.addView(controls);
        root.addView(rail);

        sheet = new SheetView();
        root.addView(sheet, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = ui.row();
        ui.pad(footer, 10, 5, 10, 5);
        previous = ui.button("Previous", false, () -> turn(-1));
        footer.addView(previous);
        status = ui.text("Open an EPUB to start reading", 13, ui.muted);
        status.setGravity(Gravity.CENTER);
        footer.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        next = ui.button("Next", false, () -> turn(1));
        footer.addView(next);
        root.addView(footer);
        setContentView(root);
        updateControls();
    }

    private void pickBook() {
        if (importing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startActivityForResult(intent, OPEN_BOOK);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != OPEN_BOOK || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int token = ++generation;
        importing = true;
        busy = true;
        updateControls();
        status.setText("Importing EPUB…");
        work.execute(() -> {
            String id = UUID.randomUUID().toString();
            File file = new File(folder, id + ".epub");
            try {
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Book storage is unavailable.");
                try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(file)) {
                    if (in == null) throw new IOException("The selected book is unavailable.");
                    Library.copy(in, out, 96L * 1024 * 1024);
                }
                String name;
                try (EpubBook book = new EpubBook(file)) { name = book.title; }
                books.edit().putString("title." + id, name).apply();
                runOnUiThread(() -> {
                    if (current(token)) {
                        importing = false;
                        open(id);
                    }
                });
            } catch (Exception failure) {
                file.delete();
                report(token, failure);
            }
        });
    }

    private void shelf() {
        if (importing) return;
        ArrayList<String> ids = new ArrayList<>();
        for (String key : books.getAll().keySet()) if (key.startsWith("title.")) ids.add(key.substring(6));
        Collections.sort(ids, (a, b) -> bookTitle(a).compareToIgnoreCase(bookTitle(b)));
        if (ids.isEmpty()) {
            pickBook();
            return;
        }
        String[] names = ids.stream().map(this::bookTitle).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Play Books").setItems(names, (dialog, index) -> open(ids.get(index)))
                .setPositiveButton("Import EPUB", (dialog, which) -> pickBook()).setNegativeButton("Close", null).show();
    }

    private String bookTitle(String id) { return books.getString("title." + id, "Untitled book"); }

    private void open(String id) {
        bookId = id;
        books.edit().putString("last", id).apply();
        loaded = null;
        title.setText(bookTitle(id));
        sheet.invalidate();
        reflow();
    }

    private void chapters() {
        if (loaded == null || busy || turning) return;
        String[] entries = new String[loaded.chapters()];
        for (int i = 0; i < entries.length; i++) entries[i] = "Section " + (i + 1);
        new AlertDialog.Builder(this).setTitle("Book sections").setSingleChoiceItems(entries, loaded.chapter(),
                (dialog, index) -> {
                    dialog.dismiss();
                    request(index, 0);
                }).setNegativeButton("Close", null).show();
    }

    private void pageShape() {
        new AlertDialog.Builder(this).setTitle("Page shape")
                .setSingleChoiceItems(new String[]{"Fit screen", "Square (1:1)"}, square ? 1 : 0, (dialog, which) -> {
                    square = which == 1;
                    books.edit().putBoolean("square", square).apply();
                    dialog.dismiss();
                    reflow();
                }).setNegativeButton("Cancel", null).show();
    }

    private void refreshMode() { mode.setText(spread ? "Two pages" : "Single page"); }

    private void resizeText(int step) {
        textSize = Math.max(12, Math.min(32, textSize + step));
        books.edit().putFloat("textSize", textSize).apply();
        reflow();
    }

    private void reflow() {
        if (importing || bookId == null || sheet.getWidth() == 0 || sheet.getHeight() == 0) return;
        if (loaded != null) request(loaded.chapter(), offset());
        else request(books.getInt("chapter." + bookId, 0), books.getInt("offset." + bookId, 0));
    }

    private int offset() { return loaded.layout().getLineStart(loaded.breaks()[page]); }

    private void request(int chapter, int offset) {
        if (bookId == null || sheet.getWidth() == 0 || sheet.getHeight() == 0) return;
        String id = bookId;
        int token = ++generation;
        busy = true;
        status.setText("Laying out pages…");
        updateControls();
        int width = sheet.paperWidth(), height = sheet.paperHeight();
        int padding = sheet.padding();
        float size = textSize * getResources().getDisplayMetrics().scaledDensity;
        work.execute(() -> {
            try (EpubBook book = new EpubBook(new File(folder, id + ".epub"))) {
                if (generation != token) return;
                int section = Math.max(0, Math.min(chapter, book.chapters.size() - 1));
                int contentWidth = Math.max(1, width - 2 * padding), contentHeight = Math.max(1, height - 2 * padding);
                String source = HIDDEN.matcher(book.chapter(section)).replaceAll("");
                if (source.length() > 650_000) throw new IOException("This EPUB section is too large to lay out.");
                int[] remainingImages = {18};
                CharSequence text = Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY,
                        href -> remainingImages[0]-- > 0 ? book.illustration(section, href,
                                Math.min(640, contentWidth), Math.min(700, Math.max(1, contentHeight - 48))) : null, null);
                TextPaint ink = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
                ink.setColor(0xff27251f);
                ink.setTextSize(size);
                ink.setTypeface(Typeface.create("serif", Typeface.NORMAL));
                StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), ink, contentWidth)
                        .setIncludePad(false).setLineSpacing(0, 1.18f)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
                ArrayList<Integer> starts = new ArrayList<>();
                int total = layout.getLineCount();
                for (int start = 0; start < total;) {
                    starts.add(start);
                    int limit = layout.getLineTop(start) + contentHeight;
                    int end = Math.max(start + 1, Math.min(total, layout.getLineForVertical(limit)));
                    while (end < total && layout.getLineBottom(end) <= limit) end++;
                    start = end;
                }
                if (starts.isEmpty()) starts.add(0);
                starts.add(total);
                int[] breaks = starts.stream().mapToInt(Integer::intValue).toArray();
                BookPages result = new BookPages(book.title, book.rightToLeft, section,
                        book.chapters.size(), layout, breaks, width, height);
                runOnUiThread(() -> {
                    if (!current(token)) return;
                    loaded = result;
                    int line = layout.getLineForOffset(Math.min(offset, text.length()));
                    page = 0;
                    while (page + 1 < result.count() && breaks[page + 1] <= line) page++;
                    if (spread) page -= page % 2;
                    busy = false;
                    showPage();
                    if (pendingTurn != 0) {
                        float side = pendingTurn;
                        pendingTurn = 0;
                        finishTurn(side);
                    }
                });
            } catch (Exception failure) { report(token, failure); }
        });
    }

    private boolean current(int token) { return token == generation && !isFinishing() && !isDestroyed(); }

    private void report(int token, Exception failure) {
        runOnUiThread(() -> {
            if (!current(token)) return;
            importing = false;
            busy = false;
            if (pendingTurn != 0) {
                pendingTurn = 0;
                sheet.setRotationY(0);
                sheet.setAlpha(1);
                turning = false;
            }
            status.setText(loaded == null ? "Open an EPUB to start reading" : "Section unavailable");
            updateControls();
            new AlertDialog.Builder(this).setTitle("Could not open EPUB")
                    .setMessage(failure.getLocalizedMessage()).setPositiveButton("OK", null).show();
        });
    }

    private void turn(int direction) {
        if (busy || turning || loaded == null) return;
        int step = spread ? 2 : 1;
        int target = page + direction * step;
        int section = loaded.chapter();
        boolean nextSection = target < 0 || target >= loaded.count();
        if (nextSection && (section + direction < 0 || section + direction >= loaded.chapters())) return;
        turning = true;
        float side = direction * (loaded.rtl() ? -1 : 1);
        sheet.setCameraDistance(8000 * getResources().getDisplayMetrics().density);
        sheet.setPivotX(side > 0 ? sheet.getWidth() * .18f : sheet.getWidth() * .82f);
        sheet.animate().rotationY(-74 * side).alpha(.55f).setDuration(135).withEndAction(() -> {
            if (nextSection) {
                pendingTurn = side;
                request(section + direction, direction > 0 ? 0 : Integer.MAX_VALUE);
            } else {
                page = target;
                showPage();
                finishTurn(side);
            }
        }).start();
    }

    private void finishTurn(float side) {
        sheet.setRotationY(74 * side);
        sheet.animate().rotationY(0).alpha(1).setDuration(155).withEndAction(() -> turning = false).start();
    }

    private void showPage() {
        title.setText(loaded.title());
        status.setText(String.format(Locale.ROOT, "Section %d/%d · %d–%d of %d", loaded.chapter() + 1,
                loaded.chapters(), page + 1, Math.min(loaded.count(), page + (spread ? 2 : 1)), loaded.count()));
        books.edit().putInt("chapter." + bookId, loaded.chapter()).putInt("offset." + bookId, offset()).apply();
        updateControls();
        sheet.invalidate();
    }

    private void updateControls() {
        previous.setEnabled(!busy && loaded != null && (page > 0 || loaded.chapter() > 0));
        next.setEnabled(!busy && loaded != null && (page + (spread ? 2 : 1) < loaded.count()
                || loaded.chapter() + 1 < loaded.chapters()));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("book", bookId);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        generation++;
        sheet.animate().cancel();
        work.shutdownNow();
        super.onDestroy();
    }

    private final class SheetView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float down;

        SheetView() {
            super(PlayBooksActivity.this);
            setContentDescription("Book pages. Swipe to turn.");
        }

        int padding() { return Math.max(5, Math.min(ui.dp(24), Math.min(paperWidth(), paperHeight()) / 8)); }
        int paperWidth() {
            int width = Math.max(1, (getWidth() - ui.dp(24)) / (spread ? 2 : 1));
            return Math.max(1, Math.min(width, square ? getHeight() - ui.dp(16) : ui.dp(680)));
        }
        int paperHeight() {
            int height = Math.max(1, getHeight() - ui.dp(16));
            return square ? Math.max(1, Math.min(height, paperWidth())) : height;
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { post(PlayBooksActivity.this::reflow); }

        @Override protected void onDraw(Canvas canvas) {
            int width = paperWidth(), height = paperHeight(), leaves = spread ? 2 : 1;
            float left = (getWidth() - width * leaves) / 2f, top = (getHeight() - height) / 2f;
            for (int slot = 0; slot < leaves; slot++) {
                float x = left + slot * width;
                paint.setColor(PAPER);
                canvas.drawRoundRect(x + 1, top, x + width - 1, top + height, ui.dp(6), ui.dp(6), paint);
                if (loaded == null || loaded.width() != width || loaded.height() != height) continue;
                int index = page + (loaded.rtl() ? leaves - slot - 1 : slot);
                if (index >= loaded.count()) continue;
                int start = loaded.layout().getLineTop(loaded.breaks()[index]);
                int end = loaded.layout().getLineTop(loaded.breaks()[index + 1]);
                int inset = padding();
                canvas.save();
                canvas.clipRect(x + inset, top + inset, x + width - inset,
                        top + inset + Math.min(end - start, height - 2 * inset));
                canvas.translate(x + inset, top + inset - start);
                loaded.layout().draw(canvas);
                canvas.restore();
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { down = event.getX(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float delta = event.getX() - down;
                int direction = Math.abs(delta) > ui.dp(36) ? (delta < 0 ? 1 : -1)
                        : (event.getX() > getWidth() / 2f ? 1 : -1);
                if (loaded == null) return performClick();
                turn(loaded.rtl() ? -direction : direction);
                return true;
            }
            return true;
        }

        @Override public boolean performClick() {
            super.performClick();
            if (loaded == null) pickBook();
            return true;
        }
    }
}
