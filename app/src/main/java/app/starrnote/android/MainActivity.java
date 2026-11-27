package app.starrnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int IMPORT = 10, INSERT = 11, PDF = 12, PNG = 13, BACKUP = 14, RESTORE = 15,
            VIDEO = 16, SYNC_CREATE = 17, SYNC_CONNECT = 18, AUDIO_IMPORT = 19, DEVICE_UNLOCK = 20;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> pendingSave;
    private volatile long saveRevision;
    private final Runnable refreshResults = this::refreshLibraryResults;
    private final List<Runnable> afterTask = new ArrayList<>();
    private final Runnable saveAction = this::saveWhenIdle;
    StudioStyle ui;
    SharedPreferences prefs;
    Library library;
    Notebook open;
    NotebookEditor editor;
    Notebook.Page pageClipboard;
    List<Notebook.Mark> markClipboard = new ArrayList<>();
    private AudioPanel audio;
    private android.os.CountDownTimer timer;
    private long timerEnd;
    private Runnable afterUnlock;
    private boolean dirty, favorites, loading;
    private String filter = "", query = "";
    private final HandwritingIndex handwritingIndex = new HandwritingIndex();
    private TextView smartSearch;
    private final Runnable indexSearch = this::indexHandwriting;
    private enum Collection { ALL, NOTEBOOKS, PDFS }
    private Collection collection = Collection.ALL;
    private enum Shelf { ALL, UNCATEGORIZED, UNTAGGED, TRASH }
    private Shelf shelf = Shelf.ALL;
    private String tag = "";
    private int sort;
    private LinearLayout results;
    private final List<String> tabIds = new ArrayList<>();
    private final Map<String, InkView.Viewport> tabViewports = new LinkedHashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        prefs = getSharedPreferences("starrnote", MODE_PRIVATE);
        audio = new AudioPanel(this);
        applyTheme();
        setContentView(ui.text("Opening library…", 18, ui.ink));
        DISK.execute(() -> {
            try {
                Library opened = new Library(this);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    library = opened;
                    finishOpening(state);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    new AlertDialog.Builder(this).setTitle("Library unavailable")
                            .setMessage(detail(failure)).setPositiveButton("Close", (d, i) -> finish())
                            .setCancelable(false).show();
                });
            }
        });
    }

    private void finishOpening(Bundle state) {
        String previous = state == null ? "" : state.getString("note", "");
        for (String id : prefs.getString("editor.tabs", "").split(",")) {
            if (findNotebook(id) != null && !tabIds.contains(id)) tabIds.add(id);
        }
        NotebookWidget.publish(this, library.notebooks, null);
        if (state == null && openWidget(getIntent())) return;
        for (Notebook note : library.notebooks) {
            if (note.id.equals(previous)) {
                showEditor(note);
                return;
            }
        }
        showLibrary();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (library != null) openWidget(intent);
    }

    private boolean openWidget(Intent intent) {
        if (NotebookWidget.NEW.equals(intent.getAction())) {
            showLibrary();
            newNotebook();
            return true;
        }
        if (!NotebookWidget.OPEN.equals(intent.getAction())) return false;
        Notebook note = findNotebook(intent.getStringExtra(NotebookWidget.NOTE));
        if (note == null || note.trashed) showLibrary();
        else showEditor(note);
        return true;
    }

    private void applyTheme() {
        int theme = prefs.getInt("theme", StudioStyle.tabletWorkspace(this) ? StudioStyle.CHARCOAL : 0);
        ui = new StudioStyle(this, theme, prefs.getInt("custom_primary", 0xff244b55),
                prefs.getInt("custom_accent", 0xfff5e5a4));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(ui.page);
        boolean lightNavigation = android.os.Build.VERSION.SDK_INT >= 27;
        getWindow().setNavigationBarColor(lightNavigation ? ui.page : ui.primary);
        boolean dark = theme == 1 || theme == StudioStyle.CHARCOAL;
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!dark && lightNavigation) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (open != null) state.putString("note", open.id);
    }

    @Override protected void onPause() {
        super.onPause();
        if (!loading) save();
    }

    @Override protected void onStop() {
        if (audio != null) audio.stopRecording();
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(indexSearch);
        main.removeCallbacks(refreshResults);
        handwritingIndex.clear();
        work.shutdown();
        if (timer != null) timer.cancel();
        if (audio != null) audio.close();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (library != null && open == null && !loading) showLibrary();
        else if (editor != null) editor.updateLayout();
    }

    @Override public void onBackPressed() {
        if (editor != null && editor.exitFocus()) return;
        if (open != null) showLibrary();
        else super.onBackPressed();
    }

    void changed(Notebook note) {
        note.updated = System.currentTimeMillis();
        bookmark();
    }

    void bookmark() {
        dirty = true;
        main.removeCallbacks(saveAction);
        main.postDelayed(saveAction, 550);
    }

    private void saveWhenIdle() {
        if (editor != null && editor.isInteracting()) {
            main.postDelayed(saveAction, 550);
        } else save();
    }

    void save() {
        main.removeCallbacks(saveAction);
        if (!dirty || library == null || loading) return;
        long revision = ++saveRevision;
        Callable<Void> snapshot = library.saveSnapshot();
        dirty = false;
        pendingSave = DISK.submit(() -> {
            // A newer snapshot contains every committed edit in this queued snapshot.
            if (revision != saveRevision) return null;
            try {
                snapshot.call();
                runOnUiThread(() -> {
                    if (!isDestroyed() && !loading && revision == saveRevision) {
                        NotebookWidget.publish(this, library.notebooks, null);
                    }
                });
                return null;
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (revision == saveRevision) dirty = true;
                    if (!isDestroyed()) message("Changes are still open. StarrNote could not save them: " + detail(failure));
                });
                throw failure;
            }
        });
    }

    void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private String detail(Exception error) {
        return error.getLocalizedMessage() == null ? error.getClass().getSimpleName() : error.getLocalizedMessage();
    }

    void showLibrary() {
        if (editor != null) editor.exitFocus();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        rememberTab();
        save();
        audio.stopRecording();
        open = null;
        editor = null;
        applyTheme();
        boolean tablet = getResources().getConfiguration().screenWidthDp >= 740;
        boolean compact = tablet && ui.charcoal;
        LinearLayout root = ui.column();
        root.setBackgroundColor(ui.page);
        LinearLayout top = ui.row();
        top.setBackgroundColor(ui.primary);
        ui.pad(top, 16, 7, 16, 7);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.starr_icon);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(ui.dp(35), ui.dp(35));
        icon.setMarginEnd(ui.dp(10));
        top.addView(logo, icon);
        TextView brand = compact ? ui.text("All notes", 20, ui.ink) : ui.title("StarrNote", 26, 0xfffffef8);
        if (compact) {
            LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(ui.dp(204), 1);
            top.addView(new View(this), spacer);
        }
        top.addView(brand, new LinearLayout.LayoutParams(0, ui.dp(49), 1));
        ui.chip(top, ui.button("Play Books", false, () -> startActivity(new Intent(this, PlayBooksActivity.class))));
        top.addView(ui.button("Settings", false, this::settings));
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = ui.column();
        ui.pad(body, 20, 7, 20, 22);
        scroll.addView(body);
        LinearLayout shelves = ui.column();
        if (tablet) {
            LinearLayout workspace = ui.row();
            ScrollView sidebar = new ScrollView(this);
            ui.pad(shelves, 10, 8, 0, 12);
            sidebar.addView(shelves);
            workspace.addView(sidebar, new LinearLayout.LayoutParams(ui.dp(ui.charcoal ? 245 : 165), -1));
            workspace.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
            root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
        } else root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout actions = ui.row();
        ui.chip(actions, ui.button("+ Notebook", true, this::newNotebook));
        ui.chip(actions, ui.button("Import", false, () -> chooseImport(IMPORT)));
        ui.chip(actions, ui.button("Sort", false, this::sortDialog));
        ui.chip(actions, ui.button(listLibrary() ? "Grid" : "List", false, () -> {
            prefs.edit().putBoolean("library.list", !listLibrary()).apply();
            showLibrary();
        }));
        if (!compact) body.addView(actions);

        EditText search = field("Search notebooks, tags or text", query);
        search.setSingleLine();
        search.setTextSize(15);
        search.setTextColor(ui.ink);
        search.setHintTextColor(ui.muted);
        ui.pad(search, 14, 8, 14, 8);
        search.setMinHeight(ui.dp(49));
        search.setBackground(ui.block(ui.surface, 15, ui.line));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                query = text.toString();
                scheduleResults();
                scheduleHandwritingSearch();
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        ui.append(compact ? shelves : body, search, compact ? 0 : 15);
        smartSearch = ui.button("Smart Search", false, this::smartSearchOptions);
        ui.append(compact ? shelves : body, smartSearch, 6);
        if (tablet) drawShelves(shelves, true);

        if (compact) {
            ui.append(body, ui.button("Back up library", false,
                    () -> create(BACKUP, "application/zip", datedBackup())), 0);
        }

        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filters = ui.row();
        addCollection(filters, "All", Collection.ALL);
        addCollection(filters, "Notebooks", Collection.NOTEBOOKS);
        addCollection(filters, "PDFs", Collection.PDFS);
        ui.chip(filters, ui.button("Pinned", favorites, () -> setFilter("", true, Collection.ALL)));
        if (!tablet) drawShelves(filters, false);
        filterScroll.addView(filters);
        ui.append(body, filterScroll, 12);
        if (compact) ui.append(body, actions, 12);
        results = ui.column();
        ui.append(body, results, 16);
        setContentView(root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        drawResults();
        scheduleHandwritingSearch();
    }

    private void smartSearchOptions() {
        Map<String, Runnable> options = new LinkedHashMap<>();
        boolean enabled = prefs.getBoolean("search.smart", false);
        options.put(enabled ? "Turn off handwriting search" : "Search handwritten notes", () -> {
            prefs.edit().putBoolean("search.smart", !enabled).apply();
            if (enabled) handwritingIndex.clear();
            updateSmartSearch();
            scheduleHandwritingSearch();
        });
        options.put("Handwriting search language", () -> new AlertDialog.Builder(this)
                .setTitle("Handwriting search language")
                .setSingleChoiceItems(Recognition.LANGUAGES, prefs.getInt("search.language", 0), (dialog, which) -> {
                    prefs.edit().putInt("search.language", which).apply();
                    dialog.dismiss();
                    updateSmartSearch();
                    scheduleHandwritingSearch();
                }).setNegativeButton("Cancel", null).show());
        options("Smart Search", options);
    }

    private void scheduleHandwritingSearch() {
        main.removeCallbacks(indexSearch);
        if (prefs.getBoolean("search.smart", false) && !query.trim().isEmpty()) main.postDelayed(indexSearch, 350);
    }

    private void indexHandwriting() {
        if (open == null && !loading && !isDestroyed()) handwritingIndex.scan(library.notebooks,
                prefs.getInt("search.language", 0), work, this::updateSmartSearch);
    }

    private void updateSmartSearch() {
        if (open != null || isDestroyed() || smartSearch == null) return;
        smartSearch.setText(prefs.getBoolean("search.smart", false) ? handwritingIndex.status() : "Smart Search");
        scheduleResults();
    }

    private void scheduleResults() {
        main.removeCallbacks(refreshResults);
        main.postDelayed(refreshResults, 160);
    }

    private void refreshLibraryResults() {
        if (open == null && results != null && !loading && !isDestroyed()) drawResults();
    }

    private void addCollection(LinearLayout filters, String label, Collection type) {
        boolean active = !favorites && filter.isEmpty() && tag.isEmpty() && shelf == Shelf.ALL && collection == type;
        ui.chip(filters, ui.button(label, active, () -> setFilter("", false, type)));
    }

    private void setFilter(String folder, boolean pinned, Collection type) {
        filter = folder;
        tag = "";
        shelf = Shelf.ALL;
        favorites = pinned;
        collection = type;
        showLibrary();
    }

    private boolean listLibrary() {
        return prefs.getBoolean("library.list", getResources().getConfiguration().screenWidthDp >= 740);
    }

    private void drawShelves(LinearLayout container, boolean vertical) {
        Map<String, Runnable> actions = new LinkedHashMap<>();
        if (vertical && ui.charcoal) actions.put("All notes", () -> setShelf(Shelf.ALL));
        actions.put("Uncategorized", () -> setShelf(Shelf.UNCATEGORIZED));
        actions.put("Untagged", () -> setShelf(Shelf.UNTAGGED));
        actions.put("Trash", () -> setShelf(Shelf.TRASH));
        actions.put("Folders", () -> chooseGroup(true));
        actions.put("Tags", () -> chooseGroup(false));
        for (Map.Entry<String, Runnable> action : actions.entrySet()) {
            boolean active = action.getKey().equalsIgnoreCase(shelf.name())
                    || (action.getKey().equals("All notes") && shelf == Shelf.ALL && filter.isEmpty() && tag.isEmpty())
                    || (action.getKey().equals("Folders") && !filter.isEmpty())
                    || (action.getKey().equals("Tags") && !tag.isEmpty());
            TextView button = ui.button(action.getKey(), active, action.getValue());
            if (vertical && ui.charcoal) {
                button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                button.setBackground(ui.ripple(active ? 0xff283b40 : ui.page, 0, 0));
                button.setTextColor(active ? ui.lime : ui.ink);
            }
            if (vertical) ui.append(container, button, 6); else ui.chip(container, button);
        }
    }

    private void setShelf(Shelf value) {
        shelf = value;
        filter = tag = "";
        collection = Collection.ALL;
        favorites = false;
        showLibrary();
    }

    private void chooseGroup(boolean folders) {
        TreeSet<String> groups = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Notebook note : library.notebooks) {
            if (note.trashed) continue;
            for (String value : (folders ? new String[]{note.folder} : note.tags.split(","))) {
                if (!value.trim().isEmpty()) groups.add(value.trim());
            }
        }
        if (groups.isEmpty()) {
            message(folders ? "Assign a folder in Notebook details" : "Add tags in Notebook details");
            return;
        }
        Map<String, Runnable> choices = new LinkedHashMap<>();
        for (String group : groups) choices.put(group, () -> {
            filter = folders ? group : "";
            tag = folders ? "" : group;
            shelf = Shelf.ALL;
            collection = Collection.ALL;
            favorites = false;
            showLibrary();
        });
        options(folders ? "Folders" : "Tags", choices);
    }

    private void drawResults() {
        results.removeAllViews();
        List<Notebook> visible = new ArrayList<>();
        String term = query.toLowerCase(Locale.ROOT).trim();
        for (Notebook note : library.notebooks) {
            if (note.trashed != (shelf == Shelf.TRASH)) continue;
            if (shelf == Shelf.UNCATEGORIZED && !note.folder.isEmpty()) continue;
            if (shelf == Shelf.UNTAGGED && !note.tags.isEmpty()) continue;
            if (!tag.isEmpty() && !java.util.Arrays.stream(note.tags.split(",")).anyMatch(value -> value.trim().equalsIgnoreCase(tag))) continue;
            if (favorites && !note.favorite || !filter.isEmpty() && !filter.equalsIgnoreCase(note.folder)) continue;
            if (collection != Collection.ALL && (collection == Collection.PDFS) != containsPdf(note)) continue;
            if (term.isEmpty() || matches(note, term)) visible.add(note);
        }
        Comparator<Notebook> order = switch (sort) {
            case 1 -> Comparator.comparing(note -> note.name.toLowerCase(Locale.ROOT));
            case 2 -> Comparator.comparingLong(note -> note.updated);
            default -> Comparator.comparingLong((Notebook note) -> note.updated).reversed();
        };
        visible.sort(Comparator.comparing((Notebook note) -> !note.favorite).thenComparing(order));
        String label = switch (shelf) {
            case TRASH -> "Trash";
            case UNCATEGORIZED -> "Uncategorized";
            case UNTAGGED -> "Untagged";
            case ALL -> favorites ? "Pinned" : !filter.isEmpty() ? filter : !tag.isEmpty() ? tag : switch (collection) {
            case ALL -> "Library";
            case NOTEBOOKS -> "Notebooks";
            case PDFS -> "PDFs";
            };
        };
        if (!ui.charcoal || getResources().getConfiguration().screenWidthDp < 740 || !label.equals("Library")) {
            results.addView(ui.title(label, 25, ui.ink));
        }
        if (visible.isEmpty()) {
            TextView empty = ui.text(library.notebooks.isEmpty()
                    ? "Create a notebook or import a file to get started." : "No notebooks match.", 15, ui.muted);
            ui.pad(empty, 16, 23, 16, 23);
            empty.setBackground(ui.block(ui.surface, 16, ui.line));
            ui.append(results, empty, 14);
            return;
        }
        if (listLibrary()) {
            for (Notebook note : visible) ui.append(results, listRow(note, term), 7);
            return;
        }
        int columns = getResources().getConfiguration().screenWidthDp >= 690 ? 3 : 2;
        for (int start = 0; start < visible.size(); start += columns) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.TOP);
            for (int cell = 0; cell < columns; cell++) {
                int position = start + cell;
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (cell > 0) params.leftMargin = ui.dp(10);
                row.addView(position < visible.size() ? card(visible.get(position), term) : new View(this), params);
            }
            ui.append(results, row, 12);
        }
    }

    private View listRow(Notebook note, String term) {
        LinearLayout row = ui.row();
        row.setBackground(ui.charcoal ? ui.ripple(ui.page, 0, ui.line) : ui.ripple(ui.surface, 14, ui.line));
        ui.pad(row, 10, 8, 12, 8);
        View cover = ui.charcoal && !note.deviceLock && !containsPdf(note) ? notebookPreview(note) : colorCover(note);
        row.addView(cover, new LinearLayout.LayoutParams(ui.dp(ui.charcoal ? 58 : 48), ui.dp(ui.charcoal ? 74 : 48)));
        LinearLayout text = ui.column();
        ui.pad(text, 16, 0, 6, 0);
        TextView title = ui.text(note.name, ui.charcoal ? 14 : 16, ui.ink);
        title.setMaxLines(2);
        text.addView(title);
        text.addView(ui.text(java.text.DateFormat.getDateInstance().format(new Date(note.updated)), ui.charcoal ? 11 : 12, ui.muted));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (ui.charcoal) {
            TextView options = ui.button("⌄", false, () -> noteOptions(note));
            options.setContentDescription("Options for " + note.name);
            row.addView(options);
        }
        row.setContentDescription(note.name + (note.trashed ? ", in trash" : ", " + pageCount(note)));
        row.setOnClickListener(v -> openFromLibrary(note, term));
        row.setOnLongClickListener(v -> { noteOptions(note); return true; });
        return row;
    }

    private View colorCover(Notebook note) {
        TextView cover = ui.text(containsPdf(note) ? "PDF" : "✦", 12, 0xfffffff0);
        cover.setGravity(Gravity.CENTER);
        cover.setBackground(ui.block(Notebook.COVERS[Math.floorMod(note.cover, Notebook.COVERS.length)], 8, 0));
        return cover;
    }

    private View notebookPreview(Notebook note) {
        PageThumbnail thumbnail = new PageThumbnail(this, file -> null);
        thumbnail.bind(note.pages.get(0), null);
        return thumbnail;
    }

    private String pageCount(Notebook note) { return note.pages.size() == 1 ? "1 page" : note.pages.size() + " pages"; }

    private void openFromLibrary(Notebook note, String term) {
        if (note.trashed) { noteOptions(note); return; }
        if (!term.isEmpty() && !note.deviceLock) {
            for (int i = 0; i < note.pages.size(); i++) {
                if (matchesPage(note.pages.get(i), term)) {
                    note.currentPage = i;
                    break;
                }
            }
        }
        showEditor(note);
    }

    private boolean containsPdf(Notebook note) {
        for (Notebook.Page page : note.pages) if (page.pdfIndex >= 0) return true;
        return false;
    }

    private boolean contains(String source, String lower) { return source.toLowerCase(Locale.ROOT).contains(lower); }

    private boolean matches(Notebook note, String term) {
        if (contains(note.name, term) || contains(note.tags, term) || contains(note.folder, term)) return true;
        if (note.deviceLock) return false;
        for (Notebook.Page page : note.pages) if (matchesPage(page, term)) return true;
        for (Notebook.Clip clip : note.clips) if (contains(clip.transcript, term) || contains(clip.name, term)) return true;
        return false;
    }

    private boolean matchesPage(Notebook.Page page, String term) {
        if (contains(page.title, term) || contains(page.indexedText, term)) return true;
        if (prefs.getBoolean("search.smart", false)
                && handwritingIndex.matches(page, term, prefs.getInt("search.language", 0))) return true;
        for (Notebook.Mark mark : page.marks) {
            if ((mark.tool.equals("Text") || mark.tool.equals("Link")) && contains(mark.value, term)) return true;
        }
        return false;
    }

    private View card(Notebook note, String term) {
        LinearLayout card = ui.column();
        ui.pad(card, 9, 9, 9, 9);
        card.setBackground(ui.ripple(ui.surface, 18, ui.line));
        LinearLayout cover = ui.column();
        int color = Notebook.COVERS[Math.floorMod(note.cover, Notebook.COVERS.length)];
        cover.setBackground(ui.block(color, 12, 0));
        ui.pad(cover, 12, 8, 12, 8);
        TextView symbol = ui.text(note.favorite ? "✦  PINNED" : "✦", 11, 0xffe7f2cc);
        cover.addView(symbol);
        TextView label = ui.title(note.name, 21, 0xfffffff0);
        label.setMaxLines(2);
        cover.addView(label, new LinearLayout.LayoutParams(-1, 0, 1));
        card.addView(cover, new LinearLayout.LayoutParams(-1, ui.dp(106)));
        String pages = note.pages.size() == 1 ? "1 page" : note.pages.size() + " pages";
        TextView meta = ui.text(note.folder.isEmpty() ? pages : note.folder + " · " + pages, 12, ui.muted);
        ui.pad(meta, 3, 0, 3, 0);
        ui.append(card, meta, 8);
        card.setContentDescription(note.name + (note.trashed ? ", in trash" : ", " + pages));
        card.setOnClickListener(v -> openFromLibrary(note, term));
        card.setOnLongClickListener(v -> { noteOptions(note); return true; });
        return card;
    }

    private void sortDialog() {
        new AlertDialog.Builder(this).setTitle("Sort notebooks")
                .setSingleChoiceItems(new String[]{"Recently edited", "Name", "Oldest edited"}, sort, (dialog, item) -> {
                    sort = item;
                    dialog.dismiss();
                    drawResults();
                }).setNegativeButton("Cancel", null).show();
    }

    EditText field(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return field;
    }

    LinearLayout form() {
        LinearLayout form = ui.column();
        ui.pad(form, 22, 7, 22, 9);
        return form;
    }

    void prompt(String title, String hint, String value, Consumer<String> done) {
        LinearLayout form = form();
        EditText entry = field(hint, value);
        form.addView(entry);
        new AlertDialog.Builder(this).setTitle(title).setView(form).setPositiveButton("Save", (d, i) -> {
            String text = entry.getText().toString().trim();
            if (!text.isEmpty()) done.accept(text);
        }).setNegativeButton("Cancel", null).show();
    }

    Spinner spinner(String[] labels, int selection) {
        return spinner(this, labels, selection);
    }

    Spinner spinner(android.content.Context context, String[] labels, int selection) {
        Spinner control = new Spinner(context);
        ArrayAdapter<String> values = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
        values.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        control.setAdapter(values);
        control.setSelection(Math.max(0, selection));
        return control;
    }

    void options(String title, Map<String, Runnable> choices) {
        String[] labels = choices.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(title).setItems(labels, (d, item) -> choices.get(labels[item]).run())
                .setNegativeButton("Close", null).show();
    }

    private void newNotebook() {
        new NotebookGallery(this, filter, note -> {
            library.notebooks.add(note);
            changed(note);
            showEditor(note);
        });
    }

    void noteOptions(Notebook note) {
        Map<String, Runnable> choices = new LinkedHashMap<>();
        if (note.trashed) {
            choices.put("Restore notebook", () -> {
                note.trashed = false;
                changed(note);
                showLibrary();
            });
            choices.put("Delete permanently", () -> new AlertDialog.Builder(this).setTitle("Delete " + note.name + "?")
                    .setMessage("This removes the notebook from your local library.")
                    .setPositiveButton("Delete permanently", (d, i) -> {
                        library.remove(note);
                        dirty = true;
                        showLibrary();
                    }).setNegativeButton("Cancel", null).show());
            options(note.name, choices);
            return;
        }
        choices.put("Rename, folder and tags", () -> editMetadata(note));
        choices.put(note.favorite ? "Unpin notebook" : "Pin notebook", () -> {
            note.favorite = !note.favorite;
            changed(note);
            if (open == null) showLibrary();
        });
        choices.put("Change cover", () -> new AlertDialog.Builder(this).setTitle("Notebook cover")
                .setItems(new String[]{"Juniper", "Indigo", "Clay", "Ochre", "Fern"}, (d, i) -> {
                    note.cover = i;
                    changed(note);
                    if (open == null) showLibrary();
                }).show());
        choices.put("Move to trash", () -> {
            note.trashed = true;
            tabIds.remove(note.id);
            tabViewports.remove(note.id);
            saveTabs();
            changed(note);
            showLibrary();
            message("Moved to trash");
        });
        options(note.name, choices);
    }

    private void editMetadata(Notebook note) {
        LinearLayout form = form();
        EditText title = field("Title", note.name);
        EditText folder = field("Folder", note.folder);
        EditText tags = field("Tags, separated by commas", note.tags);
        form.addView(title);
        form.addView(folder);
        form.addView(tags);
        new AlertDialog.Builder(this).setTitle("Notebook details").setView(form)
                .setPositiveButton("Save", (d, i) -> {
                    String name = title.getText().toString().trim();
                    if (!name.isEmpty()) note.name = name;
                    note.folder = folder.getText().toString().trim();
                    note.tags = tags.getText().toString().trim();
                    changed(note);
                    if (open == null) showLibrary();
                    else { editor.title.setText(note.name); editor.refreshTabs(); }
                }).setNegativeButton("Cancel", null).show();
    }

    void showEditor(Notebook note) {
        if (note.trashed) { showLibrary(); return; }
        if (note.deviceLock && open != note) authenticate(note, () -> displayEditor(note));
        else displayEditor(note);
    }

    private void displayEditor(Notebook note) {
        handwritingIndex.clear();
        NotebookWidget.publish(this, library.notebooks, note);
        rememberTab();
        save();
        if (open != null && open != note) audio.stopRecording();
        if (!tabIds.contains(note.id)) {
            tabIds.add(note.id);
            saveTabs();
        }
        open = note;
        setNotebookWindowLock(note.deviceLock);
        applyTheme();
        editor = new NotebookEditor(this, note);
        setContentView(editor.root);
        InkView.Viewport viewport = tabViewports.get(note.id);
        if (viewport != null) editor.restoreViewport(viewport);
    }

    private void setNotebookWindowLock(boolean locked) {
        if (locked) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    void deviceLock(Notebook note) {
        boolean enabled = !note.deviceLock;
        String label = enabled ? "Lock notebook" : "Remove device lock";
        new AlertDialog.Builder(this).setTitle(label)
                .setMessage(enabled ? "Opening this notebook will require your device PIN, pattern, or password. Exported files and backups can be opened separately."
                        : "Confirm with your device screen lock to remove protection.")
                .setPositiveButton(label, (d, i) -> authenticate(note, () -> {
                    note.deviceLock = enabled;
                    changed(note);
                    save();
                    if (open == note) setNotebookWindowLock(enabled);
                    message(enabled ? "Notebook locked" : "Device lock removed");
                })).setNegativeButton("Cancel", null).show();
    }

    private void authenticate(Notebook note, Runnable action) {
        android.app.KeyguardManager manager = getSystemService(android.app.KeyguardManager.class);
        Intent request = manager == null ? null : manager.createConfirmDeviceCredentialIntent("StarrNote", note.name);
        if (request == null) {
            message("Set up a device screen lock first");
            if (editor == null) showLibrary();
            return;
        }
        afterUnlock = action;
        startActivityForResult(request, DEVICE_UNLOCK);
    }

    private Notebook findNotebook(String id) {
        for (Notebook note : library.notebooks) if (!note.trashed && note.id.equals(id)) return note;
        return null;
    }

    List<Notebook> tabs() {
        List<Notebook> notes = new ArrayList<>();
        for (String id : tabIds) {
            Notebook note = findNotebook(id);
            if (note != null) notes.add(note);
        }
        return notes;
    }

    void openTab(Notebook note) { if (open != note) showEditor(note); }

    void closeTab(Notebook note) {
        int index = tabIds.indexOf(note.id);
        tabIds.remove(note.id);
        tabViewports.remove(note.id);
        saveTabs();
        if (open != note) {
            if (editor != null) editor.refreshTabs();
            return;
        }
        List<Notebook> remaining = tabs();
        if (remaining.isEmpty()) showLibrary();
        else showEditor(remaining.get(Math.max(0, Math.min(index, remaining.size() - 1))));
    }

    private void rememberTab() {
        if (editor != null && open != null && tabIds.contains(open.id)) tabViewports.put(open.id, editor.viewport());
    }

    private void saveTabs() { prefs.edit().putString("editor.tabs", String.join(",", tabIds)).apply(); }

    void readBackground(Notebook.Page page, Consumer<Bitmap> success) {
        readBackground(page, 0, success, true);
    }

    void readPdfDetail(Notebook.Page page, android.graphics.RectF region, float scale, Consumer<Bitmap> result) {
        work.execute(() -> {
            Bitmap detail;
            try {
                detail = library.pdfDetail(page, region, scale);
            } catch (IOException | RuntimeException failure) {
                detail = null;
            }
            Bitmap rendered = detail;
            runOnUiThread(() -> result.accept(rendered));
        });
    }

    void readBackground(Notebook.Page page, int maxEdge, Consumer<Bitmap> result, boolean showError) {
        if (page.background.isEmpty()) return;
        work.execute(() -> {
            try {
                Bitmap image = library.background(page, maxEdge);
                runOnUiThread(() -> {
                    if (!isDestroyed()) result.accept(image);
                    else if (image != null) image.recycle();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (showError) message("Page background unavailable: " + detail(failure));
                    else result.accept(null);
                });
            }
        });
    }

    private void settings() {
        Map<String, Runnable> choices = new LinkedHashMap<>();
        choices.put("Workspace theme", this::theme);
        choices.put("Back up library", () -> create(BACKUP, "application/zip", datedBackup()));
        choices.put("Restore library", () -> pick(RESTORE, "*/*", null));
        choices.put("Sync with a shared backup", this::syncOptions);
        choices.put("Play a reference video", this::video);
        options("StarrNote settings", choices);
    }

    void theme() {
        new AlertDialog.Builder(this).setTitle("Workspace theme")
                .setSingleChoiceItems(StudioStyle.NAMES,
                        prefs.getInt("theme", StudioStyle.tabletWorkspace(this) ? StudioStyle.CHARCOAL : 0), (d, i) -> {
                    d.dismiss();
                    if (i == StudioStyle.CUSTOM) customTheme(i);
                    else useTheme(i);
                }).setNegativeButton("Cancel", null).show();
    }

    private void useTheme(int theme) {
        prefs.edit().putInt("theme", theme).apply();
        applyTheme();
        if (open == null) showLibrary(); else showEditor(open);
    }

    private void customTheme(int theme) {
        LinearLayout content = form();
        int currentPrimary = prefs.getInt("custom_primary", 0xff244b55);
        int currentAccent = prefs.getInt("custom_accent", 0xfff5e5a4);
        EditText primary = field("Dark header color", String.format(Locale.ROOT, "#%06X", currentPrimary & 0xffffff));
        EditText accent = field("Light accent color", String.format(Locale.ROOT, "#%06X", currentAccent & 0xffffff));
        content.addView(ui.text("Header color", 14, 0xff254940));
        content.addView(primary);
        content.addView(ui.text("Accent color", 14, 0xff254940));
        content.addView(accent);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Custom theme").setView(content)
                .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ready -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                int main = themeColor(primary);
                int highlight = themeColor(accent);
                if (android.graphics.Color.luminance(main) > .2f) {
                    primary.setError("Choose a darker color so the header stays readable");
                    return;
                }
                if (android.graphics.Color.luminance(highlight) < .45f) {
                    accent.setError("Choose a lighter color so controls stay readable");
                    return;
                }
                prefs.edit().putInt("custom_primary", main).putInt("custom_accent", highlight).apply();
                dialog.dismiss();
                useTheme(theme);
            } catch (IllegalArgumentException invalid) {
                message("Enter both colors as six-digit hex values");
            }
        }));
        dialog.show();
    }

    private int themeColor(EditText field) {
        String value = field.getText().toString().trim().replaceFirst("^#", "");
        if (!value.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Hex color required");
        return 0xff000000 | Integer.parseInt(value, 16);
    }

    private String datedBackup() {
        return "StarrNote-" + new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".starrnote";
    }

    private void syncOptions() {
        Map<String, Runnable> choices = new LinkedHashMap<>();
        choices.put("Create shared backup", () -> create(SYNC_CREATE, "application/zip", "StarrNote-sync.starrnote"));
        choices.put("Connect to existing shared backup", () -> pick(SYNC_CONNECT, "*/*", null));
        String link = prefs.getString("sync_document", "");
        if (!link.isEmpty()) {
            choices.put("Sync now", () -> sync(Uri.parse(link), false));
            choices.put("Disconnect", () -> prefs.edit().remove("sync_document").apply());
        }
        options("Shared backup sync", choices);
    }

    void chooseImage() {
        if (editor == null) return;
        if (!editor.canInsertImage()) {
            message("Show and unlock the active layer before inserting an image");
            return;
        }
        pick(INSERT, "image/*", null);
    }
    void video() { pick(VIDEO, "video/*", null); }
    void chooseAudio() { if (open != null) pick(AUDIO_IMPORT, "audio/*", null); }
    void audio() { if (open != null) audio.show(open); }

    void studyTimer() {
        if (timer != null) {
            long remaining = Math.max(0, (timerEnd - android.os.SystemClock.elapsedRealtime() + 999) / 1000);
            new AlertDialog.Builder(this).setTitle("Study timer")
                    .setMessage(String.format(Locale.getDefault(), "%d:%02d remaining", remaining / 60, remaining % 60))
                    .setPositiveButton("Keep running", null).setNegativeButton("Stop timer", (d, i) -> {
                        if (timer != null) timer.cancel();
                        timer = null;
                    }).show();
            return;
        }
        LinearLayout form = form();
        EditText minutes = field("Minutes", "25");
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(minutes);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Study timer")
                .setView(form).setPositiveButton("Start timer", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(minutes.getText().toString().trim());
                if (value < 1 || value > 180) throw new NumberFormatException();
                long duration = value * 60000L;
                timerEnd = android.os.SystemClock.elapsedRealtime() + duration;
                timer = new android.os.CountDownTimer(duration, 1000) {
                    @Override public void onTick(long remaining) {}
                    @Override public void onFinish() {
                        timer = null;
                        message("Study timer finished");
                    }
                }.start();
                dialog.dismiss();
                message("Study timer started");
            } catch (NumberFormatException invalid) {
                minutes.setError("Enter 1 to 180 minutes");
            }
        }));
        dialog.show();
    }
    boolean isRecording() { return audio.isRecording(); }
    void stopRecording() { audio.stopRecording(); }
    void recordingChanged(boolean active) {
        if (editor != null) editor.recordingChanged(active);
    }

    void export(boolean pdf) {
        if (open == null) return;
        String name = open.name.replaceAll("[^\\p{L}\\p{N} ._-]", "_");
        create(pdf ? PDF : PNG, pdf ? "application/pdf" : "image/png", name + (pdf ? ".pdf" : ".png"));
    }

    void chooseImport(int code) {
        pick(code, "*/*", new String[]{"application/pdf", "image/*", "text/plain", "text/markdown",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"});
    }

    private void pick(int code, String type, String[] supported) {
        Intent request = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(type)
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (code == SYNC_CONNECT) request.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (supported != null) request.putExtra(Intent.EXTRA_MIME_TYPES, supported);
        startActivityForResult(request, code);
    }

    private void create(int code, String type, String name) {
        Intent request = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(request, code);
    }

    @Override protected void onActivityResult(int code, int status, Intent data) {
        super.onActivityResult(code, status, data);
        if (code == DEVICE_UNLOCK) {
            Runnable action = afterUnlock;
            afterUnlock = null;
            if (status == RESULT_OK && action != null) action.run();
            else if (editor == null) showLibrary();
            return;
        }
        if (status != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        switch (code) {
            case IMPORT -> importFile(uri);
            case INSERT -> insertImage(uri);
            case AUDIO_IMPORT -> importAudio(uri);
            case PDF, PNG -> writeExport(uri, code == PDF);
            case BACKUP -> task("Backing up library…", () -> { writeBackup(uri); return null; }, done -> message("Backup saved"));
            case RESTORE -> task("Restoring library…", () -> restore(uri), imported -> {
                showLibrary();
                message(imported + " notebooks added or updated");
            });
            case VIDEO -> startActivity(new Intent(this, VideoActivity.class).setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            case SYNC_CREATE, SYNC_CONNECT -> {
                try {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    sync(uri, code == SYNC_CREATE);
                } catch (SecurityException failure) {
                    message("Choose a writable document provider that supports saved access");
                }
            }
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == AudioPanel.PERMISSION) audio.permissionResult(grants);
    }

    private void importFile(Uri uri) {
        Notebook destination = open;
        task("Importing file…", () -> {
            String name = Library.displayName(getContentResolver(), uri, "Imported document");
            String mime = getContentResolver().getType(uri);
            return Imports.read(library, getContentResolver(), uri, name, mime);
        }, note -> {
            if (destination == null) {
                note.folder = filter;
                library.notebooks.add(note);
                changed(note);
                showEditor(note);
            } else {
                int index = destination.currentPage + 1;
                destination.pages.addAll(index, note.pages);
                destination.currentPage = index;
                changed(destination);
                editor.refreshPage();
            }
        });
    }

    void importPages() { chooseImport(IMPORT); }

    private void insertImage(Uri uri) {
        if (editor == null) return;
        task("Adding image…", () -> {
            String name = library.ingest(getContentResolver(), uri, "img");
            Bitmap bitmap = library.image(name);
            if (bitmap == null) {
                library.file(name).delete();
                throw new IOException("Unsupported image");
            }
            float proportion = bitmap.getHeight() / (float) bitmap.getWidth();
            bitmap.recycle();
            return new Imports.Image(name, proportion);
        }, image -> editor.addImage(image));
    }

    private void importAudio(Uri uri) {
        Notebook notebook = open;
        if (notebook == null) return;
        String page = notebook.current().id;
        task("Adding audio…", () -> {
            String name = Library.displayName(getContentResolver(), uri, "Imported audio");
            String asset = library.ingest(getContentResolver(), uri, "aud");
            return new Notebook.Clip(asset, name, page);
        }, clip -> {
            notebook.clips.add(clip);
            changed(notebook);
            save();
            if (open == notebook) audio.show(notebook);
        });
    }

    private void writeExport(Uri uri, boolean pdf) {
        Notebook note = open;
        if (note == null) return;
        Notebook.Page page = note.current();
        task("Exporting " + (pdf ? "PDF" : "page image") + "…", () -> {
            try (OutputStream stream = output(uri)) {
                if (pdf) Exports.pdf(library, note, stream);
                else {
                    Bitmap bitmap = Exports.page(library, page);
                    try {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("PNG encoding failed");
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
            return null;
        }, done -> message(pdf ? "PDF saved" : "Page image saved"));
    }

    private OutputStream output(Uri uri) throws IOException {
        OutputStream stream = getContentResolver().openOutputStream(uri, "wt");
        if (stream == null) throw new IOException("The selected file is unavailable");
        return stream;
    }

    private int restore(Uri uri) throws Exception {
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("The backup is unavailable");
            return library.restore(stream);
        }
    }

    private void writeBackup(Uri uri) throws Exception {
        File archive = library.archive();
        try (InputStream source = new FileInputStream(archive); OutputStream stream = output(uri)) {
            Library.copy(source, stream, Long.MAX_VALUE);
        } finally {
            archive.delete();
        }
    }

    private void sync(Uri uri, boolean fresh) {
        task("Synchronizing shared backup…", () -> {
            int imported = fresh ? 0 : restore(uri);
            writeBackup(uri);
            return imported;
        }, imported -> {
            prefs.edit().putString("sync_document", uri.toString()).apply();
            showLibrary();
            message("Sync complete · " + imported + " notebooks added or updated");
        });
    }

    <T> void task(String label, Callable<T> action, Consumer<T> success) {
        if (loading) return;
        save();
        if (dirty) {
            message("Save the open changes before continuing");
            return;
        }
        loading = true;
        java.util.concurrent.Future<?> saved = pendingSave;
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(label);
        progress.setCancelable(false);
        progress.show();
        work.execute(() -> {
            try {
                if (saved != null) saved.get();
                T result = action.call();
                runOnUiThread(() -> {
                    loading = false;
                    if (isDestroyed()) { resumeAfterTask(); return; }
                    progress.dismiss();
                    success.accept(result);
                    resumeAfterTask();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    loading = false;
                    if (isDestroyed()) { resumeAfterTask(); return; }
                    progress.dismiss();
                    new AlertDialog.Builder(this).setTitle("Operation unavailable")
                            .setMessage(detail(failure)).setPositiveButton("OK", null).show();
                    resumeAfterTask();
                });
            }
        });
    }

    void afterTask(Runnable action) {
        if (loading) afterTask.add(action);
        else action.run();
    }

    private void resumeAfterTask() {
        List<Runnable> pending = new ArrayList<>(afterTask);
        afterTask.clear();
        for (Runnable action : pending) action.run();
    }
}
