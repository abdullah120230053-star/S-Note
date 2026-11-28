package app.starrnote.android;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NotebookEditor implements InkView.Listener {
    private static final String[] BALLPOINT_STYLES = {"Solid", "Dashed", "Dotted"};
    private static final int[] COLORS = {0xff172f33, 0xff176d61, 0xff285299, 0xffaa432c,
            0xffe99f18, 0xff8659a0, 0xffc43d67, 0xff605f57, 0xfff5f2e8};
    private static final String[] COLOR_NAMES = {"Graphite", "Juniper", "Blue", "Clay", "Gold", "Iris", "Rose", "Gray", "Ivory"};
    private static final String[] TOOLS = {Brushes.PEN, Brushes.PENCIL, Brushes.HIGHLIGHTER, "Eraser", "Lasso"};
    private static final String[] FONT_NAMES = {"Sans serif", "Serif", "Monospace", "Cursive", "Condensed"};
    private static final String[] FONT_FAMILIES = {"sans-serif", "serif", "monospace", "cursive", "sans-serif-condensed"};

    private final MainActivity host;
    private final StudioStyle ui;
    private final Notebook note;
    final LinearLayout root;
    final TextView title;
    private final FrameLayout stage;
    private final LinearLayout toolbar;
    private final boolean compact;
    private final ScrollView toolDock;
    private final LinearLayout tabStrip;
    private final HorizontalScrollView tabScroll;
    private final TextView tabMenu;
    private final HorizontalScrollView shortcutScroll;
    private final LinearLayout shortcutBar;
    private final TextView zoomLevel;
    private final Runnable hideZoomLevel = this::hideZoom;
    private final ImageButton handButton;
    private final TextView selection;
    private final TextView recording;
    private final PageBrowser pageBrowser;
    private final ListView pageRail;
    private boolean showPageRail;
    private enum ToolbarEdge { TOP, BOTTOM, LEFT, RIGHT }
    private ToolbarEdge toolbarEdge = ToolbarEdge.TOP;
    private boolean verticalTools;
    private final LruCache<String, Bitmap> assets = new LruCache<>(18 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Map<String, ImageButton> buttons = new LinkedHashMap<>();
    private InkView ink;
    private InkView adjacentPage;
    private int adjacentIndex = -1;
    private float pageDrag;
    private ValueAnimator pageTurn;
    private boolean horizontalPages;
    private ImageButton toolsButton;
    private String selectedTool = Brushes.PEN;
    private String pen = Brushes.PEN, pencil = Brushes.PENCIL, highlighter = Brushes.HIGHLIGHTER;
    private String toolBeforeHand = Brushes.PEN;
    private String toolBeforeEraser = Brushes.PEN;
    private boolean focused;
    private LinearLayout workspace;
    private TextView exitFocusButton;
    private int color = COLORS[0];
    private float width = 5;

    NotebookEditor(MainActivity host, Notebook note) {
        this.host = host;
        this.note = note;
        ui = host.ui;
        horizontalPages = host.prefs.getBoolean("notes.horizontal_pages", false);
        pen = host.prefs.getString("brush.selected.pen", Brushes.PEN);
        pencil = host.prefs.getString("brush.selected.pencil", Brushes.PENCIL);
        highlighter = host.prefs.getString("brush.selected.highlighter", Brushes.HIGHLIGHTER);
        selectedTool = host.prefs.getString("brush.selected.current", pen);
        compact = StudioStyle.tabletWorkspace(host) && ui.charcoal;
        root = ui.column();
        root.setBackgroundColor(ui.page);
        tabScroll = new HorizontalScrollView(host);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = ui.row();
        ui.pad(tabStrip, compact ? 0 : 8, compact ? 2 : 5, compact ? 0 : 8, compact ? 0 : 3);
        if (compact) tabScroll.setBackgroundColor(ui.surface);
        tabScroll.addView(tabStrip);
        if (compact) {
            LinearLayout tabs = ui.row();
            tabs.setBackgroundColor(ui.surface);
            tabs.addView(tabScroll, new LinearLayout.LayoutParams(0, -2, 1));
            tabMenu = ui.text("", 13, ui.ink);
            tabMenu.setGravity(Gravity.CENTER);
            tabMenu.setBackground(ui.ripple(ui.page, 7, ui.line));
            tabMenu.setContentDescription("Open tabs");
            tabMenu.setOnClickListener(v -> showOpenTabs());
            tabs.addView(tabMenu, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(32)));
            root.addView(tabs);
        } else {
            tabMenu = null;
            root.addView(tabScroll);
        }
        refreshTabs();
        LinearLayout header = ui.row();
        ui.pad(header, 9, compact ? 0 : 6, 9, compact ? 0 : 6);
        title = ui.title(note.name, 23, ui.ink);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ui.pad(title, 6, 0, 6, 0);
        title.setOnClickListener(v -> host.noteOptions(note));
        LinearLayout upperBar = ui.column();
        if (compact) {
            headerIcon(header, "Back", "Library", host::showLibrary);
            headerIcon(header, "Image", "Insert image", host::chooseImage);
            headerIcon(header, "Audio recordings", "Audio recordings", host::audio);
            headerIcon(header, "Zoom in", "New page", this::newPage);
            handButton = headerIcon(header, "Hand", "Pan canvas", this::toggleHand);
            header.addView(new View(host), new LinearLayout.LayoutParams(0, ui.dp(36), 1));
            headerIcon(header, "Document grid", "Document grid", this::openGrid);
            headerIcon(header, "Layers", "Layers", this::layers);
            headerIcon(header, "Pages", "Pages", this::pages);
            headerIcon(header, "Eraser settings", "Customize shortcuts", this::customizeShortcuts);
            headerIcon(header, "Tools", "More", this::more);
        } else {
            ui.chip(header, ui.button("Library", false, host::showLibrary));
            header.addView(title, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
            ui.chip(header, ui.button("Pages", false, this::pages));
            header.addView(ui.button("More", false, this::more));
            LinearLayout actions = ui.row();
            ui.pad(actions, 9, 0, 9, 0);
            headerIcon(actions, "Image", "Insert image", host::chooseImage);
            headerIcon(actions, "Audio recordings", "Audio recordings", host::audio);
            headerIcon(actions, "Zoom in", "New page", this::newPage);
            handButton = headerIcon(actions, "Hand", "Pan canvas", this::toggleHand);
            upperBar.addView(header);
            upperBar.addView(actions);
        }
        if (compact) upperBar.addView(header);
        root.addView(upperBar);

        toolbar = ui.row();
        toolbar.setOnLongClickListener(v -> showToolbarPositions(null, null));
        ui.pad(toolbar, 7, 3, 7, 7);
        root.addView(toolbar);

        shortcutScroll = new HorizontalScrollView(host);
        shortcutScroll.setHorizontalScrollBarEnabled(false);
        shortcutBar = ui.row();
        ui.pad(shortcutBar, 8, 0, 8, 5);
        shortcutScroll.addView(shortcutBar);
        root.addView(shortcutScroll);

        LinearLayout workspace = ui.row();
        this.workspace = workspace;
        pageBrowser = new PageBrowser(host, note);
        pageRail = pageBrowser.rail(this::go);
        pageRail.setBackgroundColor(ui.page);
        workspace.addView(pageRail, new LinearLayout.LayoutParams(ui.dp(125), -1));
        stage = new FrameLayout(host);
        workspace.addView(stage, new LinearLayout.LayoutParams(0, -1, 1));
        toolDock = new ScrollView(host);
        toolDock.setVerticalScrollBarEnabled(false);
        toolDock.setFillViewport(false);
        toolDock.setVisibility(View.GONE);
        workspace.addView(toolDock, new LinearLayout.LayoutParams(ui.dp(compact ? 44 : 55), -1));
        root.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));

        selection = ui.button("Selection", true, this::selectionOptions);
        FrameLayout.LayoutParams selectionParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        selectionParams.setMargins(0, ui.dp(compact ? 54 : 9), ui.dp(9), 0);
        stage.addView(selection, selectionParams);
        recording = ui.button("Stop recording", true, host::stopRecording);
        FrameLayout.LayoutParams recordingParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        recordingParams.setMargins(0, 0, ui.dp(9), ui.dp(9));
        stage.addView(recording, recordingParams);
        recordingChanged(host.isRecording());
        exitFocusButton = ui.button("Exit focus", false, this::exitFocus);
        exitFocusButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams exitParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        exitParams.setMargins(ui.dp(10), ui.dp(10), 0, 0);
        stage.addView(exitFocusButton, exitParams);

        zoomLevel = ui.text("", 14, 0xffffffff);
        zoomLevel.setGravity(Gravity.CENTER);
        zoomLevel.setBackground(ui.block(0xdd202020, 8, 0));
        zoomLevel.setVisibility(View.GONE);
        FrameLayout.LayoutParams zoomParams = new FrameLayout.LayoutParams(ui.dp(68), ui.dp(34), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        zoomParams.topMargin = ui.dp(12);
        stage.addView(zoomLevel, zoomParams);

        drawToolbar();
        updateLayout();
        refreshPage();
        refreshShortcuts();
        syncSystemBars();
    }

    void refreshTabs() {
        List<Notebook> tabs = host.tabs();
        tabStrip.removeAllViews();
        int width = ui.dp(160);
        if (compact) {
            int screen = host.getResources().getDisplayMetrics().widthPixels;
            int visibleTabs = Math.max(1, Math.min(5, tabs.size()));
            int tabWidth = Math.min(ui.dp(248), (screen - ui.dp(42)) / visibleTabs);
            width = Math.max(ui.dp(120), tabWidth - ui.dp(32));
        }
        for (Notebook tab : tabs) {
            boolean active = note.id.equals(tab.id);
            LinearLayout item = ui.row();
            item.setBackground(ui.block(compact ? active ? ui.page : ui.surface : active ? ui.lime : ui.surface,
                    compact ? 8 : 12, ui.line));
            TextView name = ui.text(tab.name, compact ? 12 : 14,
                    compact ? active ? ui.ink : ui.muted : active ? 0xff16352d : ui.ink);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setMaxWidth(width);
            ui.pad(name, 10, 0, 5, 0);
            name.setContentDescription("Tab " + tab.name);
            name.setSelected(active);
            name.setOnClickListener(v -> host.openTab(tab));
            if (compact) {
                name.setGravity(Gravity.CENTER);
                item.addView(name, new LinearLayout.LayoutParams(width, ui.dp(32)));
            } else item.addView(name, new LinearLayout.LayoutParams(-2, ui.dp(42)));
            TextView close = ui.text("×", compact ? 15 : 18, compact ? ui.muted : active ? 0xff16352d : ui.ink);
            close.setGravity(Gravity.CENTER);
            close.setContentDescription("Close tab " + tab.name);
            close.setOnClickListener(v -> host.closeTab(tab));
            item.addView(close, new LinearLayout.LayoutParams(ui.dp(compact ? 32 : 40), ui.dp(compact ? 32 : 42)));
            if (compact) tabStrip.addView(item); else ui.chip(tabStrip, item);
            if (active) tabScroll.post(() -> tabScroll.smoothScrollTo(item.getLeft(), 0));
        }
        if (tabMenu != null) tabMenu.setText(tabs.size() + " ⌄");
        else ui.chip(tabStrip, ui.button("+", false, host::showLibrary));
    }

    private void showOpenTabs() {
        List<Notebook> tabs = host.tabs();
        String[] names = tabs.stream().map(tab -> tab.name).toArray(String[]::new);
        showToolPanel(new AlertDialog.Builder(panelContext()).setTitle("Open tabs")
                .setItems(names, (dialog, position) -> host.openTab(tabs.get(position)))
                .setPositiveButton("Open from library", (dialog, position) -> host.showLibrary())
                .setNegativeButton("Close", null), false);
    }

    InkView.Viewport viewport() { return ink.viewport(); }
    void restoreViewport(InkView.Viewport viewport) { ink.restoreViewport(viewport); }

    private boolean canShowPageRail() {
        return host.getResources().getConfiguration().screenWidthDp >= 650;
    }

    private boolean supportsRightDock() {
        return StudioStyle.tabletWorkspace(host);
    }

    void updateLayout() {
        syncSystemBars();
        updatePageRail();
        ToolbarEdge fallback = supportsRightDock() && host.prefs.getBoolean("editor.rightDock", true)
                ? ToolbarEdge.RIGHT : ToolbarEdge.TOP;
        ToolbarEdge edge = ToolbarEdge.valueOf(host.prefs.getString("editor.toolbarEdge", fallback.name()));
        if (edge == toolbarEdge) return;
        toolbarEdge = edge;
        verticalTools = edge == ToolbarEdge.LEFT || edge == ToolbarEdge.RIGHT;
        ((android.view.ViewGroup) toolbar.getParent()).removeView(toolbar);
        toolbar.setOrientation(verticalTools ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        toolbar.setGravity(verticalTools ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        ui.pad(toolbar, verticalTools ? 3 : 7, compact && verticalTools ? 28 : 4, verticalTools ? 3 : 7, 7);
        if (verticalTools) {
            workspace.removeView(toolDock);
            workspace.addView(toolDock, edge == ToolbarEdge.LEFT ? 0 : workspace.getChildCount(),
                    new LinearLayout.LayoutParams(ui.dp(compact ? 44 : 55), -1));
            toolDock.addView(toolbar, new ScrollView.LayoutParams(-1, -2));
        } else if (edge == ToolbarEdge.BOTTOM) {
            root.addView(toolbar, root.indexOfChild(workspace) + 1);
        } else {
            root.addView(toolbar, 2);
        }
        toolDock.setVisibility(verticalTools ? View.VISIBLE : View.GONE);
        drawToolbar();
    }

    private boolean showToolbarPositions(String settings, Runnable action) {
        Map<String, Runnable> positions = new LinkedHashMap<>();
        if (settings != null) positions.put(settings, action);
        positions.put("Top", () -> dockToolbar(ToolbarEdge.TOP));
        positions.put("Bottom", () -> dockToolbar(ToolbarEdge.BOTTOM));
        positions.put("Left", () -> dockToolbar(ToolbarEdge.LEFT));
        positions.put("Right", () -> dockToolbar(ToolbarEdge.RIGHT));
        host.options("Move toolbar", positions);
        return true;
    }

    private void dockToolbar(ToolbarEdge edge) {
        host.prefs.edit().putString("editor.toolbarEdge", edge.name()).apply();
        updateLayout();
    }

    void updatePageRail() {
        pageRail.setVisibility(!focused && canShowPageRail() && showPageRail ? View.VISIBLE : View.GONE);
        pageBrowser.refresh(true);
    }

    private void browsePages() {
        if (!canShowPageRail()) {
            pageBrowser.openDialog(this::go);
            return;
        }
        showPageRail = !showPageRail;
        updatePageRail();
    }

    private void openGrid() { pageBrowser.openGrid(this::go); }

    private void drawToolbar() {
        toolbar.removeAllViews();
        buttons.clear();
        if (verticalTools) {
            toolButton("Undo", "Undo", () -> ink.undo());
            toolButton("Redo", "Redo", () -> ink.redo());
        }
        for (String tool : TOOLS) {
            ImageButton button = toolButton(tool, tool, () -> tapTool(tool));
            if (Brushes.isInk(tool) || Brushes.isMarker(tool)) {
                button.setOnLongClickListener(v -> showToolbarPositions("Brush settings", () -> brushOptions(toolbarTool(tool))));
            } else if (tool.equals("Eraser")) {
                button.setOnLongClickListener(v -> showToolbarPositions("Eraser settings", this::eraserSettings));
            }
            buttons.put(tool, button);
        }
        if (verticalTools) {
            toolButton("Image", "Add an image", host::chooseImage);
            toolButton("Insert text", "Text tool", () -> setTool("Text"));
        }
        toolsButton = toolButton("Tools", "More writing tools", this::tools);
        if (!verticalTools) {
            toolButton("Undo", "Undo", () -> ink.undo());
            toolButton("Redo", "Redo", () -> ink.redo());
        }
        toolButton("Pen box", "Pen favorites", this::penBox);
        if (compact && verticalTools) {
            for (int index : new int[]{0, 3, 2, 1, 4}) {
                int swatch = COLORS[index];
                View chip = new View(host);
                chip.setBackground(ui.ripple(swatch, 12, ui.line));
                FrameLayout hitArea = new FrameLayout(host);
                hitArea.setContentDescription(COLOR_NAMES[index] + " ink");
                hitArea.setOnClickListener(v -> {
                    if (!Brushes.isInk(selectedTool) && !Brushes.isMarker(selectedTool)) setTool(pen);
                    setColor(swatch);
                });
                hitArea.addView(chip, new FrameLayout.LayoutParams(ui.dp(17), ui.dp(17), Gravity.CENTER));
                toolbar.addView(hitArea, new LinearLayout.LayoutParams(-1, ui.dp(29)));
            }
            toolButton("Ink color", "Ink color", this::colors);
        }
        updateToolbar();
    }

    private ImageButton iconButton(String icon, String description, Runnable action) {
        ImageButton button = new ImageButton(host);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> action.run());
        styleTool(button, icon, false);
        return button;
    }

    private ImageButton zoomButton(LinearLayout row, String icon, String description, Runnable action) {
        ImageButton button = iconButton(icon, description, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(45), ui.dp(34));
        params.setMarginEnd(ui.dp(4));
        row.addView(button, params);
        return button;
    }

    private ImageButton headerIcon(LinearLayout row, String icon, String description, Runnable action) {
        ImageButton button = iconButton(icon, description, action);
        row.addView(button, new LinearLayout.LayoutParams(ui.dp(43), ui.dp(38)));
        return button;
    }

    private ImageButton toolButton(String icon, String description, Runnable action) {
        ImageButton button = iconButton(icon, description, action);
        button.setOnLongClickListener(v -> showToolbarPositions(null, null));
        LinearLayout.LayoutParams params = verticalTools ? new LinearLayout.LayoutParams(-1, ui.dp(compact ? 38 : 44))
                : new LinearLayout.LayoutParams(0, ui.dp(44), 1);
        if (toolbar.getChildCount() > 0) {
            if (verticalTools) params.topMargin = ui.dp(compact ? 0 : 4);
            else params.setMarginStart(ui.dp(4));
        }
        toolbar.addView(button, params);
        return button;
    }

    private void updateToolbar() {
        for (Map.Entry<String, ImageButton> entry : buttons.entrySet()) {
            String tool = toolbarTool(entry.getKey());
            ImageButton button = entry.getValue();
            styleTool(button, tool, tool.equals(selectedTool));
            String description = switch (entry.getKey()) {
                case Brushes.PEN -> tool + ", pen styles";
                case Brushes.HIGHLIGHTER -> tool + ", highlighter styles";
                case Brushes.PENCIL -> tool + ", pencil styles";
                default -> tool;
            };
            button.setContentDescription(description);
            button.setTooltipText(description);
        }
        boolean secondary = !selectedTool.equals("Hand") && !buttons.containsKey(selectedTool)
                && !Brushes.isInk(selectedTool) && !Brushes.isMarker(selectedTool);
        styleTool(toolsButton, "Tools", secondary);
        toolsButton.setContentDescription(secondary ? "More writing tools, " + selectedTool + " selected" : "More writing tools");
        styleTool(handButton, "Hand", selectedTool.equals("Hand"));
    }

    private void styleTool(ImageButton button, String icon, boolean active) {
        button.setImageDrawable(new ToolIcon(icon, active ? compact ? ui.lime : 0xff16352d : ui.ink, ui.dp(compact ? 20 : 23)));
        button.setSelected(active);
        button.setBackground(compact ? ui.ripple(active ? 0xff233d43 : ui.page, 7, 0)
                : ui.ripple(active ? ui.lime : ui.surface, 14, active ? 0 : ui.line));
    }

    private String toolbarTool(String key) {
        return switch (key) {
            case Brushes.PEN -> pen;
            case Brushes.PENCIL -> pencil;
            case Brushes.HIGHLIGHTER -> highlighter;
            default -> key;
        };
    }

    private void tapTool(String key) {
        String tool = toolbarTool(key);
        if (tool.equals(selectedTool) && (Brushes.isInk(tool) || Brushes.isMarker(tool))) {
            brushOptions(tool);
        } else if (tool.equals(selectedTool) && tool.equals("Eraser")) {
            eraserSettings();
        } else setTool(tool);
    }

    private void toggleHand() {
        if (selectedTool.equals("Hand")) setTool(toolBeforeHand);
        else {
            toolBeforeHand = selectedTool;
            setTool("Hand");
        }
    }

    private void setTool(String tool) {
        if (tool.equals("Eraser") && !selectedTool.equals("Eraser")) toolBeforeEraser = selectedTool;
        selectedTool = tool;
        if (Brushes.isInk(tool) || Brushes.isMarker(tool)) {
            android.content.SharedPreferences.Editor brushes = host.prefs.edit().putString("brush.selected.current", tool);
            if (Brushes.isPen(tool)) brushes.putString("brush.selected.pen", pen = tool);
            if (Brushes.isPencil(tool)) brushes.putString("brush.selected.pencil", pencil = tool);
            if (Brushes.isMarker(tool)) brushes.putString("brush.selected.highlighter", highlighter = tool);
            brushes.apply();
        }
        ink.selectTool(tool);
        loadBrushSettings();
        updateToolbar();
    }

    private void loadBrushSettings() {
        int defaultColor = switch (selectedTool) {
            case Brushes.FOUNTAIN, Brushes.CALLIGRAPHY -> 0xff101010;
            case Brushes.WATERCOLOR -> 0xff285299;
            case Brushes.MARKER -> 0xff176d61;
            case Brushes.PASTEL -> 0xff8659a0;
            default -> Brushes.isHighlighter(selectedTool) ? 0xffffce45 : COLORS[0];
        };
        if (Paper.darkPaper(note.current()) && android.graphics.Color.luminance(defaultColor) < .22f) {
            defaultColor = 0xfff6f6f6;
        }
        color = host.prefs.getInt("brush.color." + selectedTool, defaultColor);
        width = host.prefs.getFloat("brush.width." + selectedTool, Brushes.width(selectedTool));
        ink.inkColor = color;
        ink.width = width;
        ink.inkOpacity = host.prefs.getInt("brush.opacity." + selectedTool, 100);
        ink.ballpointStyle = host.prefs.getInt("brush.ballpointStyle", 0);
        boolean adjustablePressure = Brushes.adjustablePressure(selectedTool);
        ink.stabilization = host.prefs.getInt("brush.stabilization." + selectedTool, adjustablePressure ? 20 : 0);
        ink.pressureSensitivity = adjustablePressure ? host.prefs.getInt("brush.pressure." + selectedTool, 20) : 100;
    }

    @Override public void zoomChanged(float zoom) {
        String percent = Math.round(zoom * 100) + "%";
        zoomLevel.setText(percent);
        zoomLevel.setVisibility(View.VISIBLE);
        zoomLevel.removeCallbacks(hideZoomLevel);
        zoomLevel.postDelayed(hideZoomLevel, 950);
    }

    private void hideZoom() { zoomLevel.setVisibility(View.GONE); }

    void refreshPage() {
        cancelPageTurn();
        showPage(null);
    }

    private void showPage(InkView prepared) {
        if (ink != null) stage.removeView(ink);
        Notebook.Page page = note.current();
        ink = prepared == null ? createCanvas(page) : prepared;
        InkView view = ink;
        if (page.pdfIndex >= 0) {
            view.pdfDetail = (region, scale, callback) -> host.readPdfDetail(page, region, scale, callback);
        }
        ink.selectTool(selectedTool);
        loadBrushSettings();
        zoomLevel.removeCallbacks(hideZoomLevel);
        hideZoom();
        applyPreferences();
        if (prepared == null) stage.addView(ink, 0, new FrameLayout.LayoutParams(-1, -1));
        setPageOffset(view, 0);
        selectionChanged(0);
        pageBrowser.refresh(true);
        if (prepared == null) loadPageBackground(page, view, true);
    }

    private InkView createCanvas(Notebook.Page page) {
        InkView view = new InkView(host, page, this::image, this);
        view.charcoalWorkspace = compact;
        return view;
    }

    private void loadPageBackground(Notebook.Page page, InkView view, boolean showError) {
        host.readBackground(page, 0, image -> {
            if ((ink == view || adjacentPage == view) && host.editor == this) view.setBackgroundPage(image);
            else if (image != null) image.recycle();
        }, showError);
    }

    @Override public void dragPage(float offset) {
        float extent = pageExtent();
        pageDrag = Math.max(-extent, Math.min(extent, offset));
        int next = note.currentPage + (pageDrag < 0 ? 1 : -1);
        if (next != adjacentIndex) discardAdjacentPage();
        if (next >= 0 && next < note.pages.size() && adjacentPage == null) {
            Notebook.Page page = note.pages.get(next);
            InkView view = createCanvas(page);
            adjacentPage = view;
            adjacentIndex = next;
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            stage.addView(view, 0, new FrameLayout.LayoutParams(-1, -1));
            view.postDelayed(() -> {
                if (view == ink || view == adjacentPage) loadPageBackground(page, view, view == ink);
            }, 55);
        }
        positionPages(pageDrag);
    }

    @Override public void finishPageDrag(boolean turn) {
        if (!turn || adjacentPage == null) {
            boolean append = turn && pageDrag < 0 && note.currentPage == note.pages.size() - 1
                    && host.prefs.getBoolean("auto_page", false);
            cancelPageTurn();
            if (append) go(note.pages.size());
            return;
        }
        ink.pageTurning = true;
        ValueAnimator animation = ValueAnimator.ofFloat(pageDrag, Math.copySign(pageExtent(), pageDrag));
        pageTurn = animation;
        animation.setDuration(155);
        animation.addUpdateListener(value -> positionPages((float) value.getAnimatedValue()));
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator done) {
                if (pageTurn != animation) return;
                pageTurn = null;
                note.currentPage = adjacentIndex;
                InkView next = adjacentPage;
                adjacentPage = null;
                adjacentIndex = -1;
                pageDrag = 0;
                next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                host.bookmark();
                showPage(next);
            }
        });
        animation.start();
    }

    private float pageExtent() { return horizontalPages ? stage.getWidth() : stage.getHeight(); }

    private void positionPages(float offset) {
        if (adjacentPage == null) {
            setPageOffset(ink, 0);
            return;
        }
        setPageOffset(ink, offset);
        setPageOffset(adjacentPage, offset + Math.copySign(pageExtent(), adjacentIndex - note.currentPage));
    }

    private void setPageOffset(InkView view, float offset) {
        view.setTranslationX(horizontalPages ? offset : 0);
        view.setTranslationY(horizontalPages ? 0 : offset);
    }

    private void discardAdjacentPage() {
        if (adjacentPage != null) stage.removeView(adjacentPage);
        adjacentPage = null;
        adjacentIndex = -1;
    }

    private void cancelPageTurn() {
        ValueAnimator animation = pageTurn;
        pageTurn = null;
        if (animation != null) animation.cancel();
        discardAdjacentPage();
        pageDrag = 0;
        if (ink != null) {
            ink.pageTurning = false;
            setPageOffset(ink, 0);
        }
    }

    private Bitmap image(String file) {
        Bitmap bitmap = assets.get(file);
        if (bitmap == null) {
            bitmap = host.library.image(file);
            if (bitmap != null) assets.put(file, bitmap);
        }
        return bitmap;
    }

    private void go(int index) {
        if (index == note.pages.size() && host.prefs.getBoolean("auto_page", false)) {
            appendPage(note.current().paper, "");
            return;
        }
        if (index < 0 || index >= note.pages.size() || index == note.currentPage) return;
        note.currentPage = index;
        host.bookmark();
        refreshPage();
    }

    private void newPage() {
        new AlertDialog.Builder(host).setTitle("Add page").setItems(Notebook.PAPERS, (d, item) -> {
            String paper = Notebook.PAPERS[item];
            choosePaperTheme(paper, note.current().paperTheme, theme -> appendPage(paper, "", theme));
        }).setNegativeButton("Cancel", null).show();
    }

    private void appendPage(String paper, String title) {
        appendPage(paper, title, note.current().paperTheme);
    }

    private void appendPage(String paper, String title, int theme) {
        Notebook.Page previous = note.current();
        Notebook.Page page = new Notebook.Page();
        page.paper = paper;
        page.title = title;
        page.paperTheme = theme;
        page.paperColor = previous.paperColor;
        if (paper.equals("Infinite")) {
            page.width = 2500;
            page.height = 3200;
        } else if (!previous.infinite() && previous.background.isEmpty()) {
            page.width = previous.width;
            page.height = previous.height;
        }
        note.pages.add(++note.currentPage, page);
        changed();
        refreshPage();
    }

    private void choosePaperTheme(String paper, int current, java.util.function.IntConsumer choice) {
        if (!PlannerTemplates.isPlanner(paper)) {
            choice.accept(current);
            return;
        }
        new AlertDialog.Builder(host).setTitle("Planner theme")
                .setSingleChoiceItems(PlannerTemplates.THEMES, current, (dialog, selected) -> {
                    dialog.dismiss();
                    choice.accept(selected);
                }).setNegativeButton("Cancel", null).show();
    }

    private void pages() {
        Map<String, Runnable> choices = new LinkedHashMap<>();
        choices.put("Go to page", () -> pageBrowser.openDialog(this::go));
        choices.put("Page turning", this::choosePageTurning);
        if (canShowPageRail()) choices.put(showPageRail ? "Hide page sidebar" : "Show page sidebar", this::browsePages);
        choices.put("Add page", this::newPage);
        choices.put("Import pages", host::importPages);
        choices.put("Copy page", () -> {
            try {
                host.pageClipboard = note.current().copy();
                host.message("Page copied");
            } catch (Exception failure) { host.message("Page could not be copied"); }
        });
        if (host.pageClipboard != null) choices.put("Paste page", () -> {
            try {
                note.pages.add(note.currentPage + 1, host.pageClipboard.copy());
                changed();
                go(note.currentPage + 1);
            } catch (Exception failure) { host.message("Page could not be pasted"); }
        });
        choices.put("Duplicate page", () -> {
            try {
                note.pages.add(note.currentPage + 1, note.current().copy());
                changed();
                go(note.currentPage + 1);
            } catch (Exception failure) { host.message("Page could not be duplicated"); }
        });
        if (note.currentPage > 0) choices.put("Move page earlier", () -> movePage(-1));
        if (note.currentPage < note.pages.size() - 1) choices.put("Move page later", () -> movePage(1));
        choices.put("Name this page", () -> host.prompt("Page title", "Title", note.current().title, name -> {
            note.current().title = name;
            changed();
        }));
        if (note.current().background.isEmpty()) choices.put("Change paper template", () -> new AlertDialog.Builder(host)
                .setTitle("Paper template").setItems(Notebook.PAPERS, (d, i) -> {
                    Notebook.Page page = note.current();
                    String paper = Notebook.PAPERS[i];
                    choosePaperTheme(paper, page.paperTheme, theme -> {
                        page.paper = paper;
                        page.paperTheme = theme;
                        changed();
                        ink.fit();
                    });
                }).show());
        if (PlannerTemplates.isPlanner(note.current().paper)) choices.put("Planner theme", () -> {
            Notebook.Page page = note.current();
            choosePaperTheme(page.paper, page.paperTheme, theme -> {
                page.paperTheme = theme;
                changed();
                ink.invalidate();
            });
        });
        if (note.pages.size() > 1) choices.put("Delete page", () -> new AlertDialog.Builder(host).setTitle("Delete this page?")
                .setPositiveButton("Delete", (d, i) -> {
                    note.pages.remove(note.currentPage);
                    note.current();
                    changed();
                    refreshPage();
                }).setNegativeButton("Cancel", null).show());
        host.options("Pages", choices);
    }

    private void choosePageTurning() {
        new AlertDialog.Builder(host).setTitle("Page turning")
                .setSingleChoiceItems(new String[]{"Vertical scroll", "Horizontal slide"}, horizontalPages ? 1 : 0,
                        (dialog, choice) -> {
                            cancelPageTurn();
                            horizontalPages = choice == 1;
                            host.prefs.edit().putBoolean("notes.horizontal_pages", horizontalPages).apply();
                            ink.horizontalPages = horizontalPages;
                            setTool("Read");
                            dialog.dismiss();
                            host.message(horizontalPages ? "Swipe left or right to turn pages" : "Swipe up or down to turn pages");
                        })
                .setNegativeButton("Close", null).show();
    }

    private void movePage(int offset) {
        Notebook.Page page = note.pages.remove(note.currentPage);
        note.pages.add(note.currentPage + offset, page);
        changed();
        go(note.currentPage + offset);
    }

    private void tools() {
        Map<String, Runnable> choices = new LinkedHashMap<>();
        choices.put("Pen styles", () -> brushOptions(pen));
        choices.put("Pencil styles", () -> brushOptions(pencil));
        choices.put("Highlighter and marker styles", () -> brushOptions(highlighter));
        choices.put("Pen favorites", this::penBox);
        choices.put("Eraser settings", this::eraserSettings);
        choices.put("Ruler", this::ruler);
        choices.put("Laser pointer", this::laser);
        for (String tool : new String[]{"Text", "Hand", "Line", "Rectangle", "Ellipse", "Arrow", "Table", "Tape", "Link", "Read"}) {
            choices.put(tool, () -> setTool(tool));
        }
        choices.put("Ink color", this::colors);
        choices.put("Stroke width", this::width);
        choices.put("Layers", this::layers);
        choices.put("Insert image", host::chooseImage);
        choices.put("Materials library", this::materials);
        if (!host.markClipboard.isEmpty()) choices.put("Paste selection", () -> ink.paste(host.markClipboard));
        choices.put("Ink and gesture settings", this::inkSettings);
        host.options("Writing tools", choices);
    }

    private void brushOptions(String tool) {
        if (!tool.equals(selectedTool)) setTool(tool);
        String[] family = Brushes.isPen(tool) ? Brushes.PENS : Brushes.isPencil(tool) ? Brushes.PENCILS : Brushes.MARKERS;
        LinearLayout form = host.form();
        Runnable[] refreshStyle = {() -> {}};
        TextView lineStyle = ui.button("Line style", false, () ->
                new AlertDialog.Builder(panelContext()).setTitle("Ballpoint line style")
                        .setSingleChoiceItems(BALLPOINT_STYLES, ink.ballpointStyle, (dialog, which) -> {
                            ink.ballpointStyle = which;
                            persistBrush();
                            dialog.dismiss();
                            refreshStyle[0].run();
                        }).setNegativeButton("Cancel", null).show());
        form.addView(lineStyle);
        TextView brushName = ui.text(tool, 16, panelTextColor());
        brushName.setGravity(Gravity.CENTER);
        form.addView(brushName, new LinearLayout.LayoutParams(-1, ui.dp(35)));
        LinearLayout pressureSettings = ui.column();
        TextView pressureLabel = ui.text("", 14, panelTextColor());
        SeekBar pressure = new SeekBar(panelContext());
        pressure.setContentDescription("Pressure sensitivity");
        pressureSettings.addView(pressureLabel);
        pressureSettings.addView(pressure);
        form.addView(pressureSettings);
        TextView thicknessValue = ui.text("", 14, panelTextColor());
        form.addView(thicknessValue);
        SeekBar thickness = new SeekBar(panelContext());
        thickness.setMin(1);
        thickness.setContentDescription("Stroke thickness");
        form.addView(thickness);
        TextView opacityValue = ui.text("", 14, panelTextColor());
        form.addView(opacityValue);
        SeekBar opacity = new SeekBar(panelContext());
        opacity.setContentDescription("Ink opacity");
        form.addView(opacity);
        TextView stabilizationValue = ui.text("", 14, panelTextColor());
        SeekBar stabilization = new SeekBar(panelContext());
        stabilization.setContentDescription("Stroke stabilization");
        if (Brushes.isInk(tool)) {
            form.addView(stabilizationValue);
            form.addView(stabilization);
        }
        if (Brushes.isMarker(tool)) {
            CheckBox straightLine = new CheckBox(panelContext());
            straightLine.setText("Straight highlighter strokes");
            straightLine.setChecked(ink.straight);
            straightLine.setOnCheckedChangeListener((view, enabled) -> {
                host.prefs.edit().putBoolean("straight_ink", enabled).apply();
                ink.straight = enabled;
            });
            form.addView(straightLine);
        }
        View sample = new View(host) {
            private final Paper painter = new Paper();
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                Notebook.Mark mark = new Notebook.Mark(selectedTool, "", color, width);
                mark.opacity = ink.inkOpacity;
                mark.pressureSensitivity = ink.pressureSensitivity;
                mark.lineStyle = ink.ballpointStyle;
                for (int i = 0; i <= 22; i++) {
                    mark.points.add(new Notebook.Point(getWidth() * (.14f + i * .032f),
                            getHeight() * (.5f + .17f * (float) Math.sin(i * .48f)), .3f + i * .03f));
                }
                painter.drawMark(canvas, mark, NotebookEditor.this::image);
            }
        };
        sample.setBackground(ui.block(0xfffffdf4, 12, 0xffdde8db));
        form.addView(sample, 1, new LinearLayout.LayoutParams(-1, ui.dp(45)));
        Runnable refreshColor = compact ? addBrushColors(form, sample) : sample::invalidate;
        if (!compact) ui.append(form, ui.button("Color palette", false, () -> colors(refreshColor)), 8);
        TextView savePreset = ui.button("Add to favorites", !compact, this::saveBrush);
        if (compact) {
            savePreset.setTextColor(ui.lime);
            savePreset.setBackground(ui.ripple(ui.surface, 10, ui.lime));
        }
        ui.append(form, savePreset, 6);
        Runnable refresh = () -> {
            boolean variablePressure = Brushes.adjustablePressure(selectedTool);
            brushName.setText(selectedTool);
            lineStyle.setVisibility(Brushes.BALLPOINT.equals(selectedTool) ? View.VISIBLE : View.GONE);
            lineStyle.setText("Line style · " + BALLPOINT_STYLES[ink.ballpointStyle]);
            pressureSettings.setVisibility(variablePressure ? View.VISIBLE : View.GONE);
            pressure.setProgress(ink.pressureSensitivity);
            pressureLabel.setText("Pressure sensitivity · " + ink.pressureSensitivity + "%");
            thickness.setMax(Brushes.isMarker(selectedTool) || Brushes.isPencil(selectedTool) ? 480 : 210);
            thickness.setProgress(Math.round(width * 5));
            opacity.setProgress(ink.inkOpacity);
            stabilization.setProgress(ink.stabilization);
            thicknessValue.setText(String.format(java.util.Locale.ROOT, "Thickness · %.2f mm",
                    width / Brushes.PAPER_UNITS_PER_MM));
            opacityValue.setText("Opacity · " + ink.inkOpacity + "%");
            stabilizationValue.setText("Stroke stabilization · " + ink.stabilization + "%");
            refreshColor.run();
        };
        refreshStyle[0] = refresh;
        adjust(pressure, value -> { ink.pressureSensitivity = value; persistBrush(); refresh.run(); });
        adjust(thickness, value -> { width = value / 5f; ink.width = width; persistBrush(); refresh.run(); });
        adjust(opacity, value -> { ink.inkOpacity = value; persistBrush(); refresh.run(); });
        adjust(stabilization, value -> { ink.stabilization = value; persistBrush(); refresh.run(); });
        HorizontalScrollView styleScroll = new HorizontalScrollView(host);
        styleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout styles = ui.row();
        styleScroll.addView(styles);
        Map<String, ImageButton> choices = new LinkedHashMap<>();
        for (String brush : family) {
            ImageButton icon = iconButton(brush, brush, () -> {
                setTool(brush);
                choices.forEach((name, button) -> styleTool(button, name, name.equals(brush)));
                refresh.run();
            });
            styleTool(icon, brush, brush.equals(tool));
            choices.put(brush, icon);
            styles.addView(icon, new LinearLayout.LayoutParams(ui.dp(42), ui.dp(48)));
        }
        form.addView(styleScroll, 2);
        refresh.run();
        ScrollView scroll = new ScrollView(host);
        scroll.addView(form);
        AlertDialog.Builder builder = new AlertDialog.Builder(panelContext()).setView(scroll).setPositiveButton("Done", null);
        if (!compact) builder.setTitle("Brush settings");
        showToolPanel(builder, false);
    }

    private Runnable addBrushColors(LinearLayout form, View sample) {
        ui.append(form, ui.text("Color", 14, panelTextColor()), 5);
        LinearLayout swatches = ui.row();
        TextView palette = ui.text("⌄", 15, ui.ink);
        palette.setGravity(Gravity.CENTER);
        palette.setContentDescription("Color palette");
        Runnable update = () -> {
            palette.setBackground(ui.ripple(color, 11, ui.lime));
            palette.setTextColor(android.graphics.Color.luminance(color) < .25 ? 0xffffffff : 0xff141414);
            sample.invalidate();
        };
        palette.setOnClickListener(v -> colors(update));
        swatches.addView(palette, new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        for (int index : new int[]{3, 2, 1, 4}) {
            FrameLayout hitArea = new FrameLayout(host);
            View dot = new View(host);
            dot.setBackground(ui.block(COLORS[index], 8, 0));
            hitArea.addView(dot, new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
            hitArea.setContentDescription(COLOR_NAMES[index]);
            hitArea.setOnClickListener(v -> {
                setColor(COLORS[index]);
                update.run();
            });
            swatches.addView(hitArea, new LinearLayout.LayoutParams(ui.dp(43), ui.dp(43)));
        }
        ui.append(form, swatches, 2);
        return update;
    }

    private Context panelContext() {
        return compact ? new ContextThemeWrapper(host, R.style.CharcoalPanel) : host;
    }

    private int panelTextColor() { return compact ? ui.muted : 0xff33564b; }

    private void showToolPanel(AlertDialog.Builder builder, boolean leftOfTools) {
        AlertDialog dialog = builder.show();
        if (!compact || !supportsRightDock() || dialog.getWindow() == null) return;
        android.view.Window window = dialog.getWindow();
        window.setBackgroundDrawable(ui.block(ui.surface, 12, 0));
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.view.WindowManager.LayoutParams placement = window.getAttributes();
        placement.gravity = Gravity.TOP | (toolbarEdge == ToolbarEdge.LEFT ? Gravity.START : Gravity.END);
        placement.x = ui.dp(leftOfTools ? 389 : 53);
        placement.y = ui.dp(46);
        placement.width = ui.dp(324);
        window.setAttributes(placement);
    }

    private void adjust(SeekBar bar, java.util.function.IntConsumer change) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar control, int value, boolean fromUser) {
                if (fromUser) change.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar control) {}
            @Override public void onStopTrackingTouch(SeekBar control) {}
        });
    }

    private void persistBrush() {
        host.prefs.edit().putInt("brush.color." + selectedTool, color)
                .putFloat("brush.width." + selectedTool, width)
                .putInt("brush.opacity." + selectedTool, ink.inkOpacity)
                .putInt("brush.ballpointStyle", ink.ballpointStyle)
                .putInt("brush.pressure." + selectedTool, ink.pressureSensitivity)
                .putInt("brush.stabilization." + selectedTool, ink.stabilization).apply();
    }

    private java.util.Set<String> savedBrushes() {
        return new java.util.TreeSet<>(host.prefs.getStringSet("brush.box", java.util.Collections.emptySet()));
    }

    private void saveBrush() {
        java.util.Set<String> saved = savedBrushes();
        String preset = selectedTool + "|" + color + "|" + width + "|" + ink.inkOpacity + "|" + ink.stabilization
                + "|" + ink.pressureSensitivity + "|" + (Brushes.BALLPOINT.equals(selectedTool) ? ink.ballpointStyle : 0);
        if (saved.size() >= 40 && !saved.contains(preset)) {
            host.message("Remove a saved brush to make room");
            return;
        }
        saved.add(preset);
        host.prefs.edit().putStringSet("brush.box", saved).apply();
        host.message("Saved to pen favorites");
    }

    private void penBox() {
        penBox(false);
    }

    private record BrushPreset(String key, String tool, int color, float width, int opacity,
                               int smoothing, int pressure, int style) {
        static BrushPreset read(String value) {
            String[] parts = value.split("\\|");
            if (parts.length < 5 || parts.length > 7) return null;
            if (!Brushes.isInk(parts[0]) && !Brushes.isMarker(parts[0])) return null;
            try {
                float width = Float.parseFloat(parts[2]);
                if (!Float.isFinite(width) || width <= 0) return null;
                int style = parts.length == 7 ? Integer.parseInt(parts[6]) : 0;
                return new BrushPreset(value, parts[0], Integer.parseInt(parts[1]), width,
                        Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                        parts.length >= 6 ? Integer.parseInt(parts[5]) : 100,
                        Math.max(0, Math.min(BALLPOINT_STYLES.length - 1, style)));
            } catch (NumberFormatException invalidPreset) {
                return null;
            }
        }
    }

    private void penBox(boolean removing) {
        List<BrushPreset> presets = new ArrayList<>();
        for (String value : savedBrushes()) {
            BrushPreset preset = BrushPreset.read(value);
            if (preset != null) presets.add(preset);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(panelContext())
                .setTitle(removing ? "Remove a favorite" : "Pen favorites");
        if (presets.isEmpty()) {
            builder.setMessage("Save a brush from Brush settings to use it here.")
                    .setPositiveButton("Brush settings", (dialog, which) -> brushOptions(toolbarTool(pen)))
                    .setNegativeButton("Close", null).show();
            return;
        }
        LinearLayout cards = ui.column();
        ui.pad(cards, 17, 6, 17, 6);
        ScrollView scroll = new ScrollView(host);
        scroll.addView(cards);
        builder.setView(scroll).setNegativeButton("Close", null);
        builder.setNeutralButton(removing ? "Done" : "Manage", (dialog, which) -> penBox(!removing));
        AlertDialog dialog = builder.create();
        for (int i = 0; i < presets.size(); i += 3) {
            LinearLayout row = ui.row();
            cards.addView(row, new LinearLayout.LayoutParams(-1, -2));
            for (int column = 0; column < 3; column++) {
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
                size.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
                if (i + column >= presets.size()) {
                    row.addView(new View(host), size);
                    continue;
                }
                BrushPreset preset = presets.get(i + column);
                String weight = String.format(java.util.Locale.ROOT, "%.1f px", preset.width());
                android.widget.Button card = new android.widget.Button(panelContext());
                card.setAllCaps(false);
                card.setText(preset.tool() + "\n" + weight);
                card.setTextColor(ui.ink);
                card.setTextSize(12);
                card.setMinHeight(ui.dp(84));
                ui.pad(card, 5, 8, 5, 8);
                card.setBackground(ui.ripple(ui.surface, 10, ui.line));
                ToolIcon icon = new ToolIcon(preset.tool(), preset.color(), ui.dp(24));
                android.graphics.drawable.Drawable preview = new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{ui.block(0xfff5f2e8, 8, 0), icon});
                preview.setBounds(0, 0, ui.dp(32), ui.dp(32));
                card.setCompoundDrawables(null, preview, null, null);
                card.setCompoundDrawablePadding(ui.dp(4));
                String detail = preset.tool() + ", " + weight
                        + String.format(java.util.Locale.ROOT, ", color #%06X", preset.color() & 0xffffff)
                        + ", " + preset.opacity() + "% opacity, " + preset.smoothing() + "% smoothing"
                        + (Brushes.adjustablePressure(preset.tool()) ? ", " + preset.pressure() + "% pressure" : "")
                        + (Brushes.BALLPOINT.equals(preset.tool()) ? ", " + BALLPOINT_STYLES[preset.style()] : "");
                card.setContentDescription(removing ? "Remove " + detail : detail);
                card.setTooltipText(detail);
                card.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (removing) removeFavorite(preset);
                    else {
                        applyFavorite(preset);
                        host.message(preset.tool() + " selected");
                    }
                });
                row.addView(card, size);
            }
        }
        dialog.show();
    }

    private void applyFavorite(BrushPreset preset) {
        setTool(preset.tool());
        ink.inkColor = color = preset.color();
        ink.width = width = preset.width();
        ink.inkOpacity = preset.opacity();
        ink.stabilization = preset.smoothing();
        ink.pressureSensitivity = preset.pressure();
        if (Brushes.BALLPOINT.equals(preset.tool())) ink.ballpointStyle = preset.style();
        persistBrush();
    }

    private void removeFavorite(BrushPreset preset) {
        new AlertDialog.Builder(panelContext()).setTitle("Remove this favorite?")
                .setMessage(preset.tool()).setNegativeButton("Cancel", (dialog, which) -> penBox(true))
                .setPositiveButton("Remove", (dialog, which) -> {
                    java.util.Set<String> saved = savedBrushes();
                    saved.remove(preset.key());
                    host.prefs.edit().putStringSet("brush.box", saved).apply();
                    penBox(!saved.isEmpty());
                }).show();
    }

    private void setColor(int selected) {
        color = selected;
        ink.inkColor = selected;
        host.prefs.edit().putInt("brush.color." + selectedTool, selected).apply();
    }

    private void more() {
        Map<String, Runnable> options = new LinkedHashMap<>();
        options.put("Focus mode", this::enterFocus);
        options.put("Move toolbar", () -> showToolbarPositions(null, null));
        options.put("Document grid", this::openGrid);
        options.put("Note overview", this::noteOverview);
        options.put(note.deviceLock ? "Remove device lock" : "Lock notebook", () -> host.deviceLock(note));
        options.put("Layers", this::layers);
        options.put("Notebook details", () -> host.noteOptions(note));
        options.put("Audio recordings", host::audio);
        options.put("Import audio", host::chooseAudio);
        options.put("Study timer", host::studyTimer);
        options.put("Customize shortcuts", this::customizeShortcuts);
        options.put("Handwriting recognition", this::recognize);
        options.put("Extract printed text (OCR)", this::ocr);
        options.put("Export notebook PDF", () -> host.export(true));
        options.put("Export page image", () -> host.export(false));
        options.put("Watch reference video", host::video);
        options.put("Materials library", this::materials);
        options.put("Zoom controls", () -> {
            Map<String, Runnable> zoom = new LinkedHashMap<>();
            zoom.put("Zoom in", () -> ink.zoomBy(1.25f));
            zoom.put("Zoom out", () -> ink.zoomBy(1 / 1.25f));
            host.options("Zoom controls", zoom);
        });
        options.put("Fit page", ink::fit);
        if (note.current().infinite()) options.put("Fit written area", ink::frameInk);
        options.put("Magnifier", ink::magnify);
        if (note.current().background.isEmpty() && !note.current().infinite()
                && !PlannerTemplates.isPlanner(note.current().paper)) options.put("Extend canvas", ink::extend);
        options.put("Ink and gesture settings", this::inkSettings);
        options.put("Workspace theme", host::theme);
        if (!host.markClipboard.isEmpty()) options.put("Paste selection", () -> ink.paste(host.markClipboard));
        if (compact) {
            String[] labels = options.keySet().toArray(new String[0]);
            showToolPanel(new AlertDialog.Builder(panelContext()).setTitle("More functions")
                    .setItems(labels, (dialog, item) -> options.get(labels[item]).run())
                    .setNegativeButton("Close", null), false);
        } else host.options("Notebook tools", options);
    }

    private void noteOverview() {
        int marks = 0, images = 0;
        for (Notebook.Page page : note.pages) {
            marks += page.marks.size();
            for (Notebook.Mark mark : page.marks) if (mark.tool.equals("Image")) images++;
        }
        String overview = note.pages.size() + " pages\n" + marks + " marks (" + images + " images)\n"
                + note.clips.size() + " audio clips" + (note.folder.isEmpty() ? "" : "\nFolder  " + note.folder)
                + (note.tags.isEmpty() ? "" : "\nTags  " + note.tags);
        new AlertDialog.Builder(host).setTitle(note.name).setMessage(overview)
                .setPositiveButton("Done", null).setNeutralButton("Page grid", (d, i) -> pageBrowser.openGrid(this::go)).show();
    }

    private void enterFocus() {
        focused = true;
        for (int i = 0; i < root.getChildCount(); i++) {
            View view = root.getChildAt(i);
            if (view != workspace && view != toolbar) view.setVisibility(View.GONE);
        }
        exitFocusButton.setVisibility(View.VISIBLE);
        updatePageRail();
        syncSystemBars();
    }

    boolean exitFocus() {
        if (!focused) return false;
        focused = false;
        for (int i = 0; i < root.getChildCount(); i++) root.getChildAt(i).setVisibility(View.VISIBLE);
        refreshShortcuts();
        exitFocusButton.setVisibility(View.GONE);
        toolDock.setVisibility(verticalTools ? View.VISIBLE : View.GONE);
        toolbar.setVisibility(View.VISIBLE);
        updatePageRail();
        syncSystemBars();
        return true;
    }

    private void syncSystemBars() {
        boolean immersive = compact && supportsRightDock();
        android.view.Window window = host.getWindow();
        int fullScreen = android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
        if (focused || immersive) window.addFlags(fullScreen); else window.clearFlags(fullScreen);
        int hideNavigation = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(immersive ? flags | hideNavigation : flags & ~hideNavigation);
    }

    private Map<String, Runnable> shortcuts() {
        Map<String, Runnable> actions = new LinkedHashMap<>();
        actions.put("Layers", this::layers);
        actions.put("Document grid", () -> pageBrowser.openGrid(this::go));
        actions.put("Pen box", this::penBox);
        actions.put("Ink color", this::colors);
        actions.put("Study timer", host::studyTimer);
        actions.put("Audio recordings", host::audio);
        actions.put("Insert text", () -> setTool("Text"));
        actions.put("Eraser settings", this::eraserSettings);
        return actions;
    }

    private void refreshShortcuts() {
        shortcutBar.removeAllViews();
        java.util.Set<String> selected = host.prefs.getStringSet("editor.shortcuts", java.util.Collections.emptySet());
        for (Map.Entry<String, Runnable> shortcut : shortcuts().entrySet()) {
            if (selected.contains(shortcut.getKey())) {
                zoomButton(shortcutBar, shortcut.getKey(), "Shortcut: " + shortcut.getKey(), shortcut.getValue());
            }
        }
        shortcutScroll.setVisibility(!focused && shortcutBar.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void customizeShortcuts() {
        java.util.Set<String> saved = new java.util.LinkedHashSet<>(
                host.prefs.getStringSet("editor.shortcuts", java.util.Collections.emptySet()));
        String[] entries = shortcuts().keySet().toArray(new String[0]);
        boolean[] checked = new boolean[entries.length];
        for (int i = 0; i < entries.length; i++) checked[i] = saved.contains(entries[i]);
        new AlertDialog.Builder(host).setTitle("Editor shortcuts")
                .setMultiChoiceItems(entries, checked, (d, item, enable) -> {
                    if (enable) saved.add(entries[item]); else saved.remove(entries[item]);
                }).setPositiveButton("Apply", (d, item) -> {
                    host.prefs.edit().putStringSet("editor.shortcuts", saved).apply();
                    refreshShortcuts();
                }).setNegativeButton("Cancel", null).show();
    }

    private void eraserSettings() {
        if (!selectedTool.equals("Eraser")) setTool("Eraser");
        LinearLayout form = host.form();
        form.addView(ui.text("Erase mode", 14, panelTextColor()));
        Spinner modes = panelSpinner(new String[]{"Whole stroke", "Partial stroke", "Circle selection"}, ink.eraseMode);
        form.addView(modes);
        TextView measure = ui.text("Size · " + Math.round(ink.eraseSize) + " px", 14, panelTextColor());
        form.addView(measure);
        SeekBar size = new SeekBar(panelContext());
        size.setMin(2);
        size.setMax(110);
        size.setProgress(Math.round(ink.eraseSize));
        size.setContentDescription("Eraser size");
        form.addView(size);
        adjust(size, value -> measure.setText("Size · " + value + " px"));
        form.addView(ui.text("Erase only", 14, panelTextColor()));
        Spinner filter = panelSpinner(new String[]{"All editable marks", "Highlighters", "Tape"}, ink.eraseFilter);
        form.addView(filter);
        CheckBox pressure = new CheckBox(panelContext());
        pressure.setText("Pressure-sensitive eraser");
        pressure.setChecked(ink.erasePressure);
        form.addView(pressure);
        CheckBox scribble = new CheckBox(panelContext());
        scribble.setText("Scribble to erase");
        scribble.setChecked(ink.scribble);
        form.addView(scribble);
        CheckBox auto = new CheckBox(panelContext());
        auto.setText("Return to previous tool after erasing");
        auto.setChecked(host.prefs.getBoolean("eraser.auto", false));
        form.addView(auto);
        ScrollView scroll = new ScrollView(host);
        scroll.addView(form);
        showToolPanel(new AlertDialog.Builder(panelContext()).setTitle("Eraser settings").setView(scroll).setPositiveButton("Apply", (d, i) -> {
            host.prefs.edit().putInt("eraser.mode", modes.getSelectedItemPosition())
                    .putInt("eraser.filter", filter.getSelectedItemPosition())
                    .putFloat("eraser.size", size.getProgress()).putBoolean("eraser.pressure", pressure.isChecked())
                    .putBoolean("scribble_erase", scribble.isChecked()).putBoolean("eraser.auto", auto.isChecked()).apply();
            applyPreferences();
        }).setNegativeButton("Cancel", null), false);
    }

    private Spinner panelSpinner(String[] labels, int selected) {
        return host.spinner(panelContext(), labels, selected);
    }

    @Override public void eraserFinished() {
        if (selectedTool.equals("Eraser") && host.prefs.getBoolean("eraser.auto", false)) setTool(toolBeforeEraser);
    }

    private void colors() { colors(() -> {}, false); }

    private void colors(Runnable updated) { colors(updated, true); }

    private void colors(Runnable updated, boolean besideBrush) {
        LinearLayout content = host.form();
        EditText custom = new EditText(panelContext());
        custom.setHint("Custom hex color, e.g. #176D61");
        custom.setSingleLine();
        LinearLayout swatches = ui.row();
        for (int i = 0; i < COLORS.length; i++) {
            if (i > 0 && i % 5 == 0) {
                ui.append(content, swatches, 8);
                swatches = ui.row();
            }
            View swatch = new View(host);
            swatch.setBackground(ui.ripple(COLORS[i], 19, 0xffd6ddd8));
            swatch.setContentDescription(COLOR_NAMES[i]);
            final int selected = COLORS[i];
            swatch.setOnClickListener(v -> {
                setColor(selected);
                custom.setText("");
                updated.run();
                swatch.setAlpha(.6f);
                host.message(swatch.getContentDescription().toString());
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.dp(42), ui.dp(42));
            params.rightMargin = ui.dp(6);
            swatches.addView(swatch, params);
        }
        ui.append(content, swatches, 8);
        ColorField field = new ColorField(host, color,
                selected -> custom.setText(String.format(java.util.Locale.ROOT, "#%06X", selected & 0xffffff)));
        ui.append(content, field, 9);
        field.getLayoutParams().height = ui.dp(135);
        SeekBar hue = new SeekBar(panelContext());
        hue.setMax(359);
        hue.setProgress(field.hue());
        hue.setContentDescription("Color hue");
        adjust(hue, field::hue);
        content.addView(hue);
        content.addView(custom);
        ui.append(content, ui.button("Save color", false, () -> {
            try {
                int selected = readHex(custom);
                java.util.Set<String> saved = savedColors();
                if (saved.size() >= 32 && !saved.contains(String.valueOf(selected))) {
                    host.message("Remove a color from your set to make room");
                    return;
                }
                saved.add(String.valueOf(selected));
                host.prefs.edit().putStringSet("brush.colors", saved).apply();
                host.message("Color saved");
            } catch (IllegalArgumentException invalid) {
                custom.setError("Enter a six-digit hex color");
            }
        }), 4);
        ui.append(content, ui.button("Saved color set", false, () -> colorSet(() -> {
            custom.setText("");
            updated.run();
        })), 4);
        ScrollView scroll = new ScrollView(host);
        scroll.addView(content);
        showToolPanel(new AlertDialog.Builder(panelContext()).setTitle("Ink color").setView(scroll).setPositiveButton("Done", (d, i) -> {
            String value = custom.getText().toString().trim();
            if (!value.isEmpty()) {
                try {
                    setColor(readHex(custom));
                    updated.run();
                } catch (IllegalArgumentException invalid) { host.message("Enter a six-digit hex color"); }
            }
        }), besideBrush);
    }

    private int readHex(EditText field) {
        String value = field.getText().toString().trim();
        if (value.isEmpty()) return color;
        return android.graphics.Color.parseColor(value.startsWith("#") ? value : "#" + value);
    }

    private java.util.Set<String> savedColors() {
        return new java.util.TreeSet<>(host.prefs.getStringSet("brush.colors", java.util.Collections.emptySet()));
    }

    private void colorSet(Runnable updated) {
        Map<String, Runnable> colors = new LinkedHashMap<>();
        List<String> values = new ArrayList<>(savedColors());
        List<String> names = new ArrayList<>();
        for (String value : values) {
            int rgb = Integer.parseInt(value);
            String name = String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xffffff);
            names.add(name);
            colors.put(name, () -> { setColor(rgb); updated.run(); });
        }
        if (colors.isEmpty()) {
            host.message("Use Save color to build your color set");
            return;
        }
        colors.put("Remove a saved color", () -> new AlertDialog.Builder(host).setTitle("Remove color")
                .setItems(names.toArray(new String[0]), (d, i) -> {
                    java.util.Set<String> saved = savedColors();
                    saved.remove(values.get(i));
                    host.prefs.edit().putStringSet("brush.colors", saved).apply();
                }).setNegativeButton("Cancel", null).show());
        host.options("Saved color set", colors);
    }

    private void width() {
        LinearLayout content = host.form();
        SeekBar widthControl = new SeekBar(host);
        widthControl.setMax(Brushes.isMarker(selectedTool) || Brushes.isPencil(selectedTool) ? 96 : 42);
        widthControl.setMin(1);
        widthControl.setProgress(Math.round(width));
        widthControl.setContentDescription("Stroke width");
        TextView preview = ui.text("", 15, ui.muted);
        preview.setText(Math.round(width) + " px");
        content.addView(preview);
        widthControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { preview.setText(value + " px"); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        content.addView(widthControl);
        new AlertDialog.Builder(host).setTitle("Stroke width").setView(content)
                .setPositiveButton("Apply", (d, i) -> {
                    width = widthControl.getProgress();
                    ink.width = width;
                    host.prefs.edit().putFloat("brush.width." + selectedTool, width).apply();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void layerDetails(Notebook.Layer layer) {
        Notebook.Page page = note.current();
        Map<String, Runnable> actions = new LinkedHashMap<>();
        actions.put("Write on this layer", () -> {
            ink.selectLayer(layer.id);
            host.message(layer.visible && !layer.locked ? layer.name + " selected" : "Turn on and unlock this layer to write");
        });
        actions.put(layer.visible ? "Hide layer" : "Show layer", () -> {
            layer.visible = !layer.visible;
            ink.clearSelection();
            changed();
            ink.invalidate();
        });
        actions.put(layer.locked ? "Unlock layer" : "Lock layer", () -> {
            layer.locked = !layer.locked;
            ink.clearSelection();
            changed();
        });
        actions.put("Rename layer", () -> host.prompt("Layer name", "Name", layer.name, name -> {
            layer.name = name;
            changed();
        }));
        actions.put("Layer opacity · " + layer.opacity + "%", () -> {
            SeekBar opacity = new SeekBar(panelContext());
            opacity.setMax(100);
            opacity.setProgress(layer.opacity);
            opacity.setContentDescription("Layer opacity");
            new AlertDialog.Builder(panelContext()).setTitle("Layer opacity").setView(opacity)
                    .setPositiveButton("Apply", (dialog, which) -> {
                        layer.opacity = opacity.getProgress();
                        changed();
                    }).setNegativeButton("Cancel", null).show();
        });
        actions.put("Delete layer", () -> new AlertDialog.Builder(panelContext()).setTitle("Move layer to Deleted layers?")
                .setPositiveButton("Delete", (dialog, which) -> deleteLayer(page, layer))
                .setNegativeButton("Cancel", null).show());
        int index = page.layers.indexOf(layer);
        if (index < page.layers.size() - 1) actions.put("Raise layer", () -> moveLayer(layer, index + 1));
        if (index > 0) actions.put("Lower layer", () -> moveLayer(layer, index - 1));
        if (layer.id.equals(ink.activeLayer()) && layer.visible && !layer.locked) {
            actions.put("Clear active layer", () -> new AlertDialog.Builder(host).setTitle("Clear " + layer.name + "?")
                    .setPositiveButton("Clear", (d, i) -> ink.clearLayer()).setNegativeButton("Cancel", null).show());
        }
        host.options(layer.name, actions);
    }

    private void moveLayer(Notebook.Layer layer, int position) {
        note.current().layers.remove(layer);
        note.current().layers.add(position, layer);
        changed();
        ink.invalidate();
    }

    private void layers() {
        Notebook.Page page = note.current();
        Map<String, Runnable> actions = new LinkedHashMap<>();
        if (!page.deletedLayers.isEmpty()) {
            actions.put("Deleted layers", () -> {
                List<Notebook.DeletedLayer> deleted = new ArrayList<>(page.deletedLayers);
                String[] names = deleted.stream().map(item -> "Restore " + item.layer().name).toArray(String[]::new);
                new AlertDialog.Builder(panelContext()).setTitle("Deleted layers").setItems(names, (dialog, which) -> {
                    Notebook.DeletedLayer item = deleted.get(which);
                    if (!page.deletedLayers.remove(item)) return;
                    page.layers.add(Math.min(page.layers.size(), Math.max(0, item.position())), item.layer());
                    page.marks.addAll(item.marks());
                    ink.keepLayerInHistory(item.marks());
                    ink.selectLayer(item.layer().id);
                    changed();
                }).setNegativeButton("Close", null).show();
            });
        }
        actions.put("+ Add layer", () -> host.prompt("Add layer", "Layer name", "Layer " + (page.layers.size() + 1), name -> {
            Notebook.Layer layer = new Notebook.Layer(name);
            page.layers.add(layer);
            ink.selectLayer(layer.id);
            changed();
        }));
        for (int i = page.layers.size() - 1; i >= 0; i--) {
            Notebook.Layer layer = page.layers.get(i);
            String label = (i + 1) + ". " + layer.name + (layer.id.equals(ink.activeLayer()) ? " · active" : "")
                    + (layer.visible ? "" : " · hidden") + (layer.locked ? " · locked" : "");
            actions.put(label, () -> layerDetails(layer));
        }
        host.options("Layers", actions);
    }

    private void deleteLayer(Notebook.Page page, Notebook.Layer layer) {
        int position = page.layers.indexOf(layer);
        if (position < 0) return;
        List<Notebook.Mark> removed = new ArrayList<>();
        for (Notebook.Mark mark : page.marks) if (layer.id.equals(mark.layer)) removed.add(mark);
        page.deletedLayers.add(new Notebook.DeletedLayer(layer, position, removed));
        page.marks.removeAll(removed);
        page.layers.remove(layer);
        if (page.layers.isEmpty()) page.layers.add(new Notebook.Layer("Ink"));
        ink.forgetLayerHistory(layer.id);
        ink.clearSelection();
        if (ink.activeLayer().equals(layer.id)) ink.selectLayer(page.layers.get(0).id);
        changed();
    }

    private void ruler() {
        EditText angle = new EditText(panelContext());
        angle.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        angle.setText(Float.toString(Float.isNaN(ink.rulerAngle) ? 0 : ink.rulerAngle));
        angle.setHint("Angle in degrees");
        new AlertDialog.Builder(panelContext()).setTitle("Ruler angle").setView(angle)
                .setPositiveButton("Show ruler", (dialog, which) -> {
                    try {
                        float value = Float.parseFloat(angle.getText().toString());
                        if (!Float.isFinite(value)) throw new NumberFormatException();
                        ink.rulerAngle = value % 360;
                        if (!Brushes.isInk(selectedTool)) setTool(pen);
                        ink.invalidate();
                    } catch (NumberFormatException invalid) { host.message("Enter a valid angle in degrees"); }
                }).setNeutralButton("Hide ruler", (dialog, which) -> {
                    ink.rulerAngle = Float.NaN;
                    ink.invalidate();
                }).setNegativeButton("Cancel", null).show();
    }

    private void laser() {
        LinearLayout form = host.form();
        int duration = host.prefs.getInt("laser.duration", 1500);
        TextView value = ui.text("", 14, panelTextColor());
        SeekBar slider = new SeekBar(panelContext());
        slider.setMin(1);
        slider.setMax(20);
        slider.setProgress(duration / 250);
        slider.setContentDescription("Laser trail duration");
        java.util.function.IntConsumer preview = tick -> value.setText(String.format(java.util.Locale.ROOT,
                "Trail duration · %.2f s", tick / 4f));
        preview.accept(slider.getProgress());
        adjust(slider, preview);
        form.addView(value);
        form.addView(slider);
        new AlertDialog.Builder(panelContext()).setTitle("Laser pointer").setView(form)
                .setPositiveButton("Use laser", (dialog, which) -> {
                    ink.laserDuration = slider.getProgress() * 250;
                    host.prefs.edit().putInt("laser.duration", ink.laserDuration).apply();
                    setTool("Laser pointer");
                }).setNegativeButton("Cancel", null).show();
    }

    private void materials() {
        Map<String, Runnable> packs = new LinkedHashMap<>();
        packs.put("Study stickers", () -> stickers("Study stickers",
                new String[]{"✦  Focus star", "✓  Checkmark", "⚑  Flag", "◎  Target", "?  Review question", "★  Priority"}));
        packs.put("Planning stickers", () -> stickers("Planning stickers",
                new String[]{"◷  Clock", "☀  Morning", "☾  Evening", "➜  Next", "▶  Play", "♡  Wellness", "✿  Reflection"}));
        packs.put("Diagram stickers", () -> stickers("Diagram stickers",
                new String[]{"↗  Upward trend", "↔  Compare", "⇄  Exchange", "△  Triangle", "○  Circle", "⚡  Energy"}));
        for (String template : new String[]{PlannerTemplates.LECTURE_REVIEW, PlannerTemplates.LAB_LOG,
                PlannerTemplates.HABIT_TRACKER}) {
            packs.put("Add " + template, () -> choosePaperTheme(template, note.current().paperTheme,
                    theme -> appendPage(template, "", theme)));
        }
        host.options("Materials library", packs);
    }

    private void stickers(String title, String[] entries) {
        new AlertDialog.Builder(host).setTitle(title).setItems(entries, (d, i) -> {
            Notebook.Page page = note.current();
            ink.add("Sticker", entries[i].substring(0, 1), "",
                    Math.min(300, page.width * .37f), Math.min(180, page.height * .24f), 68, 0);
            setTool("Lasso");
            host.message("Use lasso to move the sticker");
        }).setNegativeButton("Close", null).show();
    }

    private void inkSettings() {
        LinearLayout form = host.form();
        String[] keys = {"stylus_only", "zoom_lock", "scribble_erase", "straight_ink", "auto_page"};
        String[] titles = {"Write with stylus only", "Lock pinch zoom", "Scribble to erase",
                "Straight highlighter and tape", "Add a page when advancing past the end"};
        List<CheckBox> boxes = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            CheckBox option = new CheckBox(host);
            option.setText(titles[i]);
            option.setChecked(host.prefs.getBoolean(keys[i], keys[i].equals("scribble_erase")));
            boxes.add(option);
            form.addView(option);
        }
        new AlertDialog.Builder(host).setTitle("Ink and gestures").setView(form).setPositiveButton("Apply", (d, item) -> {
            android.content.SharedPreferences.Editor prefs = host.prefs.edit();
            for (int i = 0; i < keys.length; i++) prefs.putBoolean(keys[i], boxes.get(i).isChecked());
            prefs.apply();
            applyPreferences();
        }).setNegativeButton("Cancel", null).show();
    }

    private void applyPreferences() {
        ink.horizontalPages = horizontalPages;
        ink.stylusOnly = host.prefs.getBoolean("stylus_only", false);
        ink.zoomLocked = host.prefs.getBoolean("zoom_lock", false);
        ink.scribble = host.prefs.getBoolean("scribble_erase", true);
        ink.straight = host.prefs.getBoolean("straight_ink", false);
        ink.eraseMode = host.prefs.getInt("eraser.mode", 0);
        ink.eraseFilter = host.prefs.getInt("eraser.filter", 0);
        ink.eraseSize = host.prefs.getFloat("eraser.size", 16);
        ink.erasePressure = host.prefs.getBoolean("eraser.pressure", false);
    }

    @Override public void changed() {
        if (ink != null) ink.invalidatePaper();
        host.changed(note);
        pageBrowser.refresh(false);
    }

    boolean isInteracting() { return ink != null && ink.isInteracting(); }

    void recordingChanged(boolean active) {
        recording.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    @Override public void selectionChanged(int count) {
        selection.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        selection.setText(count + " selected");
    }

    private void selectionOptions() {
        Map<String, Runnable> choices = new LinkedHashMap<>();
        Notebook.Mark text = ink.editableText();
        if (text != null) choices.put("Edit text", () -> editText(text));
        Notebook.Mark image = ink.editableImage();
        if (image != null) {
            choices.put("Resize image", () -> resizeImage(image));
            choices.put("Crop image", () -> cropImage(image));
            if (!image.crop.equals(Notebook.ImageCrop.FULL)) {
                choices.put("Reset image crop", () -> ink.editImage(image, 1, Notebook.ImageCrop.FULL));
            }
        }
        choices.put("Copy selection", () -> {
            host.markClipboard = ink.copySelection();
            host.message("Selection copied");
        });
        choices.put("Cut selection", () -> {
            host.markClipboard = ink.copySelection();
            ink.deleteSelection();
        });
        choices.put("Duplicate selection", () -> ink.paste(ink.copySelection()));
        choices.put("Recognize selected handwriting", this::recognize);
        choices.put("Delete selection", ink::deleteSelection);
        host.options("Selection", choices);
    }

    private void resizeImage(Notebook.Mark mark) {
        LinearLayout form = host.form();
        TextView value = ui.text("100% of current size", 14, ui.ink);
        SeekBar size = new SeekBar(panelContext());
        size.setMin(10);
        size.setMax(300);
        size.setProgress(100);
        size.setContentDescription("Image size");
        adjust(size, percent -> value.setText(percent + "% of current size"));
        form.addView(value);
        form.addView(size);
        new AlertDialog.Builder(panelContext()).setTitle("Resize image").setView(form)
                .setPositiveButton("Apply", (dialog, which) -> {
                    int percent = size.getProgress();
                    if (percent != 100) ink.editImage(mark, percent / 100f, mark.crop);
                }).setNegativeButton("Cancel", null).show();
    }

    private void cropImage(Notebook.Mark mark) {
        Bitmap source = image(mark.value);
        if (source == null) { host.message("The image is unavailable"); return; }
        LinearLayout form = host.form();
        int[] trim = {Math.round(mark.crop.left() * 100), Math.round(mark.crop.top() * 100),
                Math.round((1 - mark.crop.right()) * 100), Math.round((1 - mark.crop.bottom()) * 100)};
        View preview = new View(host) {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            private final android.graphics.RectF frame = new android.graphics.RectF();
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float scale = Math.min(getWidth() / (float) source.getWidth(), getHeight() / (float) source.getHeight());
                float w = source.getWidth() * scale, h = source.getHeight() * scale;
                float x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                frame.set(x, y, x + w, y + h);
                canvas.drawBitmap(source, null, frame, paint);
                int save = canvas.save();
                canvas.clipOutRect(x + w * trim[0] / 100, y + h * trim[1] / 100,
                        x + w * (100 - trim[2]) / 100, y + h * (100 - trim[3]) / 100);
                paint.setColor(0xaa000000);
                canvas.drawRect(frame, paint);
                canvas.restoreToCount(save);
                paint.setColor(android.graphics.Color.WHITE);
            }
        };
        preview.setContentDescription("Image crop preview");
        form.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(155)));
        String[] edges = {"Left", "Top", "Right", "Bottom"};
        SeekBar[] bars = new SeekBar[4];
        TextView[] labels = new TextView[4];
        for (int i = 0; i < edges.length; i++) {
            int edge = i;
            labels[i] = ui.text(edges[i] + " · " + trim[i] + "%", 13, ui.ink);
            form.addView(labels[i]);
            SeekBar slider = new SeekBar(panelContext());
            bars[i] = slider;
            slider.setMax(95);
            slider.setProgress(trim[i]);
            slider.setContentDescription("Trim " + edges[i].toLowerCase(java.util.Locale.ROOT));
            form.addView(slider);
            adjust(slider, value -> {
                int opposite = (edge + 2) % 4;
                trim[edge] = Math.min(value, Math.max(0, 95 - trim[opposite]));
                if (trim[edge] != value) bars[edge].setProgress(trim[edge]);
                labels[edge].setText(edges[edge] + " · " + trim[edge] + "%");
                preview.invalidate();
            });
        }
        ScrollView scroll = new ScrollView(host);
        scroll.addView(form);
        new AlertDialog.Builder(panelContext()).setTitle("Crop image").setView(scroll)
                .setPositiveButton("Apply", (dialog, which) -> {
                    Notebook.ImageCrop crop = new Notebook.ImageCrop(trim[0] / 100f, trim[1] / 100f,
                            (100 - trim[2]) / 100f, (100 - trim[3]) / 100f);
                    if (!crop.equals(mark.crop)) ink.editImage(mark, 1, crop);
                }).setNegativeButton("Cancel", null).show();
    }

    private void editText(Notebook.Mark mark) {
        LinearLayout form = host.form();
        EditText field = host.field("Text", mark.value);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(field);
        boolean linked = mark.tool.equals("Link");
        String link = mark.link;
        if (link.startsWith("page:")) {
            for (int i = 0; i < note.pages.size(); i++) {
                if (("page:" + note.pages.get(i).id).equals(link)) { link = String.valueOf(i + 1); break; }
            }
        }
        EditText address = host.field("https:// address or page number", link);
        if (linked) form.addView(address);
        new AlertDialog.Builder(host).setTitle("Edit text").setView(form).setPositiveButton("Save", (d, i) -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) return;
            String target = linked ? resolve(address.getText().toString().trim()) : "";
            if (target == null) {
                host.message("Enter a web address or a page number in this notebook");
                return;
            }
            ink.editText(mark, value, target);
        }).setNegativeButton("Cancel", null).show();
    }

    @Override public void insert(String tool, float x, float y) {
        LinearLayout form = host.form();
        EditText text = host.field(tool.equals("Link") ? "Link label" : "Text", "");
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        form.addView(text);
        EditText link = host.field("https:// address or page number", "");
        if (tool.equals("Link")) form.addView(link);
        form.addView(ui.text("Font", 13, 0xff33564b));
        Spinner fonts = host.spinner(FONT_NAMES, host.prefs.getInt("text.font", 0));
        form.addView(fonts);
        form.addView(ui.text("Text size", 13, 0xff33564b));
        EditText size = host.field("8 to 144", String.valueOf(host.prefs.getInt("text.size", 25)));
        size.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(size);
        AlertDialog dialog = new AlertDialog.Builder(host).setTitle(tool.equals("Text") ? "Insert text" : "Insert link")
                .setView(form).setPositiveButton("Insert", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(shown -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                    String value = text.getText().toString().trim();
                    String address = link.getText().toString().trim();
                    if (tool.equals("Link")) {
                        address = resolve(address);
                        if (address == null) {
                            link.setError("Enter a web address or a page number in this notebook");
                            return;
                        }
                        if (value.isEmpty()) value = link.getText().toString().trim();
                    }
                    if (value.isEmpty()) {
                        text.setError("Enter text");
                        return;
                    }
                    int textSize;
                    try {
                        textSize = Integer.parseInt(size.getText().toString().trim());
                        if (textSize < 8 || textSize > 144) throw new NumberFormatException();
                    } catch (NumberFormatException invalid) {
                        size.setError("Enter a size from 8 to 144");
                        return;
                    }
                    int font = fonts.getSelectedItemPosition();
                    host.prefs.edit().putInt("text.font", font).putInt("text.size", textSize).apply();
                    ink.add(tool, value, address, x, y, textSize, 0, FONT_FAMILIES[font]);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private String resolve(String address) {
        if (address.matches("[0-9]{1,8}")) {
            int index = Integer.parseInt(address) - 1;
            if (index >= 0 && index < note.pages.size()) return "page:" + note.pages.get(index).id;
            return null;
        }
        Uri url = Uri.parse(address);
        return ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                && url.getHost() != null ? address : null;
    }

    @Override public void openLink(String address) {
        if (address.startsWith("page:")) {
            for (int i = 0; i < note.pages.size(); i++) {
                if (("page:" + note.pages.get(i).id).equals(address)) {
                    go(i);
                    return;
                }
            }
            host.message("The linked page is unavailable");
            return;
        }
        String url = resolve(address);
        if (url == null || url.startsWith("page:")) return;
        try {
            host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException failure) { host.message("No browser is available"); }
    }

    void addImage(Imports.Image image) {
        setTool("Lasso");
        ink.insertImage(image.name(), image.ratio());
        if (ink.editableImage() != null) selectionOptions();
    }

    boolean canInsertImage() {
        Notebook.Layer layer = note.current().layer(ink.activeLayer());
        return layer.visible && !layer.locked;
    }

    private void recognize() {
        Notebook.Page page = note.current();
        List<Notebook.Mark> selection = ink.copySelection();
        List<Notebook.Mark> source = selection.isEmpty() ? page.marks : selection;
        List<Notebook.Mark> marks = new ArrayList<>();
        for (Notebook.Mark mark : source) if (page.layer(mark.layer).visible) marks.add(mark.copy());
        new AlertDialog.Builder(host).setTitle("Handwriting language")
                .setItems(Recognition.LANGUAGES, (d, index) -> {
                    host.message("Preparing handwriting model…");
                    Recognition.handwriting(marks, index).addOnSuccessListener(host, result -> recognized(page, result))
                            .addOnFailureListener(host, failure -> host.message("Recognition failed: " + failure.getLocalizedMessage()));
                }).setNegativeButton("Cancel", null).show();
    }

    private void ocr() {
        Notebook.Page page = note.current();
        host.task("Preparing page for text recognition…", () -> Exports.page(host.library, page), bitmap ->
                Recognition.image(bitmap).addOnCompleteListener(result -> {
                    bitmap.recycle();
                    if (host.isDestroyed()) return;
                    if (result.isSuccessful()) recognized(page, result.getResult());
                    else host.message("Printed text recognition failed");
                }));
    }

    private void recognized(Notebook.Page page, String result) {
        if (note.current() != page || host.editor != this) return;
        if (result.trim().isEmpty()) {
            host.message("No text was recognized");
            return;
        }
        LinearLayout form = host.form();
        EditText text = host.field("Recognized text", result);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setMaxLines(7);
        form.addView(text);
        new AlertDialog.Builder(host).setTitle("Recognized text").setView(form)
                .setPositiveButton("Index for search", (d, i) -> {
                    page.indexedText = text.getText().toString().trim();
                    changed();
                    host.message("Recognition saved to notebook search");
                })
                .setNeutralButton("Copy text", (d, i) -> host.getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("StarrNote", text.getText().toString())))
                .setNegativeButton("Cancel", null).show();
    }
}
