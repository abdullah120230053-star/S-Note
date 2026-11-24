package app.starrnote.android;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.function.Consumer;

final class NotebookGallery {
    private record Size(String name, int width, int height) {}

    private static final Size[] SIZES = {
            new Size("Portrait, Tablet (16:10)", 840, 1344), new Size("Landscape, Tablet (16:10)", 1344, 840),
            new Size("Portrait, A4", 840, 1188), new Size("Landscape, A4", 1188, 840),
            new Size("Portrait, US Letter", 850, 1100), new Size("Landscape, US Letter", 1100, 850),
            new Size("Square", 1000, 1000)
    };
    private static final String[] CATEGORIES = {"General", "Learning", "Office", "Planners", "Materials"};
    private static final String[][] TEMPLATES = {
            {"Blank", "Rule line 1", "Rule line 2", "Ruled", "Grid small", "Grid", "Grid large", "Dotted",
                    Notebook.NOTEBOOK_PAPER, "Infinite"},
            {Notebook.NOTEBOOK_PAPER, "Cornell", "Music", "Dotted"},
            {"Blank", Notebook.NOTEBOOK_PAPER, "Grid", PlannerTemplates.TASK_CHECKLIST},
            {PlannerTemplates.DAILY_AGENDA, PlannerTemplates.DAILY_FOCUS, PlannerTemplates.WEEKLY_BOARDS,
                    PlannerTemplates.WEEKLY_TIMETABLE, PlannerTemplates.TASK_CHECKLIST, PlannerTemplates.TASK_BOARD, "Planner"},
            {PlannerTemplates.LECTURE_REVIEW, PlannerTemplates.LAB_LOG, PlannerTemplates.HABIT_TRACKER}
    };
    private static final int[] PAPER_COLORS = {0xffffffff, 0xff252525, 0xfffcf8e8, 0xffebf9ef, 0xffedf5fc};
    private static final String[] COLOR_NAMES = {"White", "Black", "Ivory", "Mint", "Blue"};
    private static final int[] PLANNER_COLORS = {0xffbcd4ac, 0xffa3c9e5, 0xffe8bda9, 0xffa0a4ad};
    private static final int ACCENT = 0xffb18b11;
    private static final int INK = 0xff202226;
    private static final int SURFACE = 0xfff0f1f5;

    private final MainActivity host;
    private final StudioStyle ui;
    private final Dialog dialog;
    private final EditText title;
    private final Spinner sizes;
    private final LinearLayout palette, categories, grid;
    private final int columns;
    private String selected = "Ruled";
    private String folder;
    private int cover, category, paperColor = PAPER_COLORS[0], plannerTheme;

    NotebookGallery(MainActivity host, String folder, Consumer<Notebook> created) {
        this.host = host;
        this.folder = folder;
        ui = new StudioStyle(host, 0);
        dialog = new Dialog(host);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        columns = host.getResources().getConfiguration().screenWidthDp >= 600 ? 4 : 2;
        LinearLayout sheet = ui.column();
        sheet.setBackground(ui.block(SURFACE, 26, 0));
        ui.pad(sheet, 16, 11, 16, 0);

        LinearLayout header = ui.row();
        header.addView(action("Close", "Cancel", dialog::dismiss), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView heading = ui.text("New handwritten note", 20, INK);
        heading.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        ui.pad(heading, 12, 0, 0, 0);
        header.addView(heading, new LinearLayout.LayoutParams(0, ui.dp(49), 1));
        header.addView(action("Check", "Create", () -> {
            String name = titleValue();
            Size size = SIZES[sizesValue()];
            Notebook note = Notebook.create(name.isEmpty() ? "Untitled notebook" : name, selected, cover);
            Notebook.Page page = note.current();
            if (!page.infinite()) {
                page.width = size.width();
                page.height = size.height();
            }
            page.paperColor = paperColor;
            page.paperTheme = plannerTheme;
            note.folder = this.folder;
            dialog.dismiss();
            created.accept(note);
        }), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        sheet.addView(header);

        ScrollView scroll = new ScrollView(host);
        scroll.setFillViewport(true);
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = ui.column();
        ui.pad(body, 8, 16, 8, 16);
        scroll.addView(body);

        LinearLayout details = card();
        title = host.field("Notebook title", "");
        title.setSingleLine();
        title.setTextColor(INK);
        title.setTextSize(16);
        title.setBackground(ui.block(0xffeeeeef, 24, 0));
        ui.pad(title, 14, 0, 14, 0);
        details.addView(title, new LinearLayout.LayoutParams(-1, ui.dp(42)));
        LinearLayout sizeRow = ui.row();
        TextView sizeLabel = ui.text("Size", 15, INK);
        sizeRow.addView(sizeLabel, new LinearLayout.LayoutParams(0, ui.dp(44), 1));
        String[] names = new String[SIZES.length];
        for (int i = 0; i < SIZES.length; i++) names[i] = SIZES[i].name();
        sizes = host.spinner(names, 0);
        sizes.setContentDescription("Page size");
        sizeRow.addView(sizes);
        ui.append(details, sizeRow, 4);
        body.addView(details);

        LinearLayout papers = card();
        palette = ui.row();
        papers.addView(palette);
        categories = ui.row();
        HorizontalScrollView categoryScroll = new HorizontalScrollView(host);
        categoryScroll.setHorizontalScrollBarEnabled(false);
        categoryScroll.addView(categories);
        ui.append(papers, categoryScroll, 6);
        grid = ui.column();
        ui.append(papers, grid, 10);
        ui.append(body, papers, 12);
        sizes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                drawTemplates();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        refresh();

        sheet.setFocusableInTouchMode(true);
        sheet.requestFocus();
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        int width = host.getResources().getConfiguration().screenWidthDp;
        int height = host.getResources().getConfiguration().screenHeightDp;
        window.setLayout(ui.dp(Math.min(720, width - 18)), ui.dp(Math.min(740, height - 18)));
        window.setGravity(Gravity.CENTER);
    }

    private String titleValue() { return title.getText().toString().trim(); }
    private int sizesValue() { return Math.max(0, sizes.getSelectedItemPosition()); }

    private ImageButton action(String icon, String label, Runnable click) {
        ImageButton button = new ImageButton(host);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setImageDrawable(new ToolIcon(icon, INK, ui.dp(20)));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setBackground(ui.ripple(0xffe6e7eb, 30, 0));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = ui.column();
        card.setBackground(ui.block(0xffffffff, 19, 0));
        ui.pad(card, 15, 13, 15, 8);
        return card;
    }

    private void refresh() {
        boolean planner = category >= 3 || (category == 2 && PlannerTemplates.isPlanner(selected));
        palette.removeAllViews();
        int[] colors = planner ? PLANNER_COLORS : PAPER_COLORS;
        for (int i = 0; i < colors.length; i++) {
            int index = i;
            FrameLayout touchArea = new FrameLayout(host);
            View dot = new View(host);
            boolean active = planner ? i == plannerTheme : colors[i] == paperColor;
            dot.setBackground(ui.block(colors[i], 30, active ? ACCENT : 0xffd4d4d2));
            touchArea.addView(dot, new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER));
            touchArea.setContentDescription(planner ? PlannerTemplates.THEMES[i] : COLOR_NAMES[i] + " paper");
            touchArea.setSelected(active);
            touchArea.setOnClickListener(v -> {
                if (planner) plannerTheme = index;
                else paperColor = colors[index];
                refresh();
            });
            palette.addView(touchArea, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(43)));
        }
        categories.removeAllViews();
        for (int i = 0; i < CATEGORIES.length; i++) {
            int index = i;
            chip(CATEGORIES[i], i == category, () -> {
                category = index;
                refresh();
            });
        }
        chip("Customize", false, this::customize);
        drawTemplates();
    }

    private void chip(String label, boolean active, Runnable click) {
        TextView chip = ui.text(label, 13, INK);
        chip.setGravity(Gravity.CENTER);
        ui.pad(chip, 10, 0, 10, 0);
        chip.setBackground(ui.ripple(active ? 0xfffff6d7 : 0xfff0f0f1, 20, 0));
        chip.setSelected(active);
        chip.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, ui.dp(35));
        params.setMarginEnd(ui.dp(6));
        categories.addView(chip, params);
    }

    private void drawTemplates() {
        grid.removeAllViews();
        String[] templates = TEMPLATES[category];
        for (int first = 0; first < templates.length; first += columns) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.TOP);
            for (int index = first; index < first + columns; index++) {
                View tile = index < templates.length ? tile(templates[index]) : new View(host);
                row.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
            }
            grid.addView(row);
        }
    }

    private View tile(String name) {
        boolean active = name.equals(selected);
        Size size = SIZES[sizesValue()];
        Notebook.Page page = new Notebook.Page();
        page.paper = name;
        page.width = size.width();
        page.height = size.height();
        page.paperColor = paperColor;
        page.paperTheme = plannerTheme;
        LinearLayout tile = ui.column();
        ui.pad(tile, 5, 5, 5, 9);
        String label = name.equals("Ruled") ? "Rule line 3" : name;
        tile.setContentDescription(label);
        tile.setSelected(active);
        tile.setOnClickListener(v -> {
            selected = name;
            refresh();
        });
        FrameLayout frame = new FrameLayout(host);
        frame.setBackground(ui.block(0xfff9f9f9, 8, active ? ACCENT : 0xffebebeb));
        ui.pad(frame, 2, 2, 2, 2);
        View preview = new View(host) {
            private final Paper paper = new Paper();
            @Override protected void onDraw(Canvas canvas) {
                if (page.infinite()) {
                    canvas.save();
                    canvas.scale(.4f, .4f);
                    paper.draw(canvas, page, null, file -> null, true);
                    canvas.restore();
                } else PageBrowser.drawPreview(canvas, paper, page, null, file -> null, getWidth(), getHeight());
            }
        };
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        if (active) {
            TextView check = ui.text("✓", 14, 0xffffffff);
            check.setGravity(Gravity.CENTER);
            check.setBackground(ui.block(ACCENT, 20, 0));
            frame.addView(check, new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.BOTTOM | Gravity.END));
        }
        tile.addView(frame, new LinearLayout.LayoutParams(-1, ui.dp(columns == 4 ? 144 : 128)));
        TextView text = ui.text(label, 13, INK);
        text.setGravity(Gravity.CENTER);
        tile.addView(text, new LinearLayout.LayoutParams(-1, ui.dp(40)));
        return tile;
    }

    private void customize() {
        LinearLayout form = host.form();
        EditText name = host.field("Folder (optional)", folder);
        Spinner covers = host.spinner(new String[]{"Juniper", "Indigo", "Clay", "Ochre", "Fern"}, cover);
        form.addView(name);
        form.addView(ui.text("Cover", 14, INK));
        form.addView(covers);
        new AlertDialog.Builder(host).setTitle("Notebook details").setView(form)
                .setPositiveButton("Save", (d, i) -> {
                    folder = name.getText().toString().trim();
                    cover = covers.getSelectedItemPosition();
                }).setNegativeButton("Cancel", null).show();
    }
}
