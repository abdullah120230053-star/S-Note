package app.starrnote.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class Notebook {
    static final String NOTEBOOK_PAPER = "Notebook Paper";
    static final String[] PAPERS = {"Blank", "Ruled", "Grid", "Dotted", "Cornell", "Planner", "Music", "Infinite",
            PlannerTemplates.DAILY_AGENDA, PlannerTemplates.DAILY_FOCUS, PlannerTemplates.WEEKLY_BOARDS,
            PlannerTemplates.WEEKLY_TIMETABLE, PlannerTemplates.TASK_CHECKLIST, PlannerTemplates.TASK_BOARD,
            "Rule line 1", "Rule line 2", "Grid small", "Grid large", NOTEBOOK_PAPER};
    static final int[] COVERS = {0xff164b46, 0xff495681, 0xff9e5245, 0xff786332, 0xff4c6745};

    String id = id();
    String name = "Untitled notebook";
    String folder = "";
    String tags = "";
    boolean favorite;
    boolean trashed;
    boolean deviceLock;
    long updated = System.currentTimeMillis();
    int cover;
    int currentPage;
    final List<Page> pages = new ArrayList<>();
    final List<Clip> clips = new ArrayList<>();

    static String id() {
        return UUID.randomUUID().toString();
    }

    static Notebook create(String title, String paper, int cover) {
        Notebook notebook = new Notebook();
        notebook.name = title;
        notebook.cover = cover;
        Page page = new Page();
        page.paper = paper;
        if (paper.equals("Infinite")) {
            page.width = 2500;
            page.height = 3200;
        }
        notebook.pages.add(page);
        return notebook;
    }

    Page current() {
        currentPage = Math.max(0, Math.min(currentPage, pages.size() - 1));
        return pages.get(currentPage);
    }

    JSONObject json() throws JSONException {
        JSONArray pageArray = new JSONArray();
        for (Page page : pages) pageArray.put(page.json());
        JSONArray clipArray = new JSONArray();
        for (Clip clip : clips) clipArray.put(clip.json());
        return new JSONObject().put("id", id).put("name", name).put("folder", folder).put("tags", tags)
                .put("favorite", favorite).put("trashed", trashed).put("deviceLock", deviceLock)
                .put("updated", updated).put("cover", cover)
                .put("currentPage", currentPage).put("pages", pageArray).put("clips", clipArray);
    }

    static Notebook read(JSONObject data) throws JSONException {
        Notebook value = new Notebook();
        value.id = data.getString("id");
        value.name = data.getString("name");
        value.folder = data.optString("folder");
        value.tags = data.optString("tags");
        value.favorite = data.optBoolean("favorite");
        value.trashed = data.optBoolean("trashed");
        value.deviceLock = data.optBoolean("deviceLock");
        value.updated = data.optLong("updated");
        value.cover = data.optInt("cover");
        value.currentPage = data.optInt("currentPage");
        JSONArray pages = data.getJSONArray("pages");
        for (int i = 0; i < pages.length(); i++) value.pages.add(Page.read(pages.getJSONObject(i)));
        if (value.pages.isEmpty()) value.pages.add(new Page());
        JSONArray clips = data.optJSONArray("clips");
        if (clips != null) {
            for (int i = 0; i < clips.length(); i++) value.clips.add(Clip.read(clips.getJSONObject(i)));
        }
        value.current();
        return value;
    }

    static final class Page {
        static final int MAX_INFINITE_EDGE = 131072;
        String id = Notebook.id();
        String paper = "Ruled";
        int paperTheme;
        int paperColor;
        String title = "";
        String indexedText = "";
        String background = "";
        int pdfIndex = -1;
        int width = 840;
        int height = 1120;
        final List<Layer> layers = new ArrayList<>();
        final List<Mark> marks = new ArrayList<>();

        Page() {
            layers.add(new Layer("Ink"));
        }

        boolean infinite() { return paper.equals("Infinite") && background.isEmpty(); }

        Page copy() throws JSONException {
            Page copy = read(json());
            copy.id = Notebook.id();
            return copy;
        }

        JSONObject json() throws JSONException {
            JSONArray layerArray = new JSONArray();
            for (Layer layer : layers) layerArray.put(layer.json());
            JSONArray markArray = new JSONArray();
            for (Mark mark : marks) markArray.put(mark.json());
            return new JSONObject().put("id", id).put("paper", paper).put("paperTheme", paperTheme).put("paperColor", paperColor)
                    .put("title", title).put("indexedText", indexedText)
                    .put("background", background).put("pdfIndex", pdfIndex).put("width", width)
                    .put("height", height).put("layers", layerArray).put("marks", markArray);
        }

        static Page read(JSONObject data) throws JSONException {
            Page value = new Page();
            value.id = data.getString("id");
            value.paper = data.optString("paper", "Ruled");
            value.paperTheme = data.optInt("paperTheme", 0);
            value.paperColor = data.optInt("paperColor", 0);
            value.title = data.optString("title");
            value.indexedText = data.optString("indexedText");
            value.background = data.optString("background");
            value.pdfIndex = data.optInt("pdfIndex", -1);
            int maxEdge = value.paper.equals("Infinite") ? MAX_INFINITE_EDGE : 18000;
            value.width = Math.max(1, Math.min(maxEdge, data.optInt("width", 840)));
            value.height = Math.max(1, Math.min(maxEdge, data.optInt("height", 1120)));
            JSONArray layers = data.getJSONArray("layers");
            value.layers.clear();
            for (int i = 0; i < layers.length(); i++) value.layers.add(Layer.read(layers.getJSONObject(i)));
            if (value.layers.isEmpty()) value.layers.add(new Layer("Ink"));
            JSONArray marks = data.getJSONArray("marks");
            for (int i = 0; i < marks.length(); i++) value.marks.add(Mark.read(marks.getJSONObject(i)));
            return value;
        }

        Layer layer(String id) {
            for (Layer layer : layers) if (layer.id.equals(id)) return layer;
            return layers.get(0);
        }
    }

    static final class Layer {
        String id = Notebook.id();
        String name;
        boolean visible = true;
        boolean locked;

        Layer(String name) { this.name = name; }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("visible", visible).put("locked", locked);
        }

        static Layer read(JSONObject data) throws JSONException {
            Layer value = new Layer(data.getString("name"));
            value.id = data.getString("id");
            value.visible = data.optBoolean("visible", true);
            value.locked = data.optBoolean("locked");
            return value;
        }
    }

    record Point(float x, float y, float pressure) {}

    static final class Mark {
        String tool;
        String layer;
        int color;
        float thickness;
        String value = "";
        String link = "";
        String font = "sans-serif";
        int opacity = 100;
        int pressureSensitivity = 100;
        boolean revealed;
        final List<Point> points = new ArrayList<>();

        Mark(String tool, String layer, int color, float thickness) {
            this.tool = tool;
            this.layer = layer;
            this.color = color;
            this.thickness = thickness;
        }

        Mark copy() {
            Mark result = new Mark(tool, layer, color, thickness);
            result.value = value;
            result.link = link;
            result.font = font;
            result.opacity = opacity;
            result.pressureSensitivity = pressureSensitivity;
            result.revealed = revealed;
            result.points.addAll(points);
            return result;
        }

        void move(float dx, float dy) {
            for (int i = 0; i < points.size(); i++) {
                Point p = points.get(i);
                points.set(i, new Point(p.x + dx, p.y + dy, p.pressure));
            }
        }

        JSONObject json() throws JSONException {
            JSONArray path = new JSONArray();
            for (Point p : points) path.put(p.x).put(p.y).put(p.pressure);
            return new JSONObject().put("tool", tool).put("layer", layer).put("color", color)
                    .put("thickness", thickness).put("value", value).put("link", link)
                    .put("font", font).put("opacity", opacity).put("pressureSensitivity", pressureSensitivity)
                    .put("revealed", revealed).put("path", path);
        }

        static Mark read(JSONObject data) throws JSONException {
            Mark mark = new Mark(data.getString("tool"), data.getString("layer"),
                    data.getInt("color"), (float) data.getDouble("thickness"));
            mark.value = data.optString("value");
            mark.link = data.optString("link");
            mark.font = data.optString("font", "sans-serif");
            mark.opacity = Math.max(0, Math.min(100, data.optInt("opacity", 100)));
            mark.pressureSensitivity = Math.max(0, Math.min(100, data.optInt("pressureSensitivity", 100)));
            mark.revealed = data.optBoolean("revealed");
            JSONArray path = data.getJSONArray("path");
            for (int i = 0; i + 2 < path.length(); i += 3) {
                mark.points.add(new Point((float) path.getDouble(i), (float) path.getDouble(i + 1),
                        (float) path.getDouble(i + 2)));
            }
            return mark;
        }
    }

    static final class Clip {
        String file;
        String name;
        String page;
        String transcript = "";
        String summary = "";

        Clip(String file, String name, String page) {
            this.file = file;
            this.name = name;
            this.page = page;
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("file", file).put("name", name).put("page", page)
                    .put("transcript", transcript).put("summary", summary);
        }

        static Clip read(JSONObject data) throws JSONException {
            Clip value = new Clip(data.getString("file"), data.getString("name"), data.optString("page"));
            value.transcript = data.optString("transcript");
            value.summary = data.optString("summary");
            return value;
        }
    }
}
