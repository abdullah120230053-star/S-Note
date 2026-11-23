package app.starrnote.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.List;
import java.util.Arrays;

final class Paper {
    private static final Typeface CURSIVE = Typeface.create("cursive", Typeface.NORMAL);
    private static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    interface Images { Bitmap get(String name); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint type = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final PlannerTemplates planner = new PlannerTemplates();

    void draw(Canvas canvas, Notebook.Page page, Bitmap background, Images images, boolean export) {
        canvas.save();
        if (!page.infinite()) canvas.clipRect(0, 0, page.width, page.height);
        canvas.drawColor(page.paperColor != 0 && !PlannerTemplates.isPlanner(page.paper)
                ? page.paperColor : export ? Color.WHITE : 0xfffffef8);
        if (background != null) {
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(background, null, new RectF(0, 0, page.width, page.height), paint);
        } else {
            ruling(canvas, page);
        }
        for (Notebook.Layer layer : page.layers) {
            if (!layer.visible) continue;
            for (Notebook.Mark mark : page.marks) {
                if (mark.layer.equals(layer.id)) drawMark(canvas, mark, images);
            }
        }
        canvas.restore();
    }

    private void ruling(Canvas canvas, Notebook.Page page) {
        paint.setColor(darkPaper(page) ? 0xff60696b : 0xffd8e3df);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(1.1f);
        int width = page.width;
        int height = page.height;
        if (planner.draw(canvas, page)) return;
        switch (page.paper) {
            case Notebook.NOTEBOOK_PAPER -> {
                float spacing = width * .026f;
                for (float y = width * .104f; y < height; y += spacing) canvas.drawLine(0, y, width, y, paint);
                paint.setColor(0xffcf8588);
                canvas.drawLine(width * .105f, 0, width * .105f, height, paint);
                paint.setColor(darkPaper(page) ? 0xff939493 : 0xffc5c5be);
                for (float offset : new float[]{.073f, .47f, .94f}) {
                    canvas.drawCircle(width * .058f, height * offset, width * .018f, paint);
                }
            }
            case "Rule line 1", "Rule line 2" -> {
                int spacing = page.paper.equals("Rule line 1") ? 17 : 25;
                for (int y = spacing; y < height; y += spacing) canvas.drawLine(14, y, width - 14, y, paint);
            }
            case "Ruled", "Cornell" -> {
                for (int y = 88; y < height - 35; y += 34) canvas.drawLine(44, y, width - 44, y, paint);
                paint.setColor(0xffe2bbb1);
                canvas.drawLine(page.paper.equals("Cornell") ? width * .29f : 65, 42,
                        page.paper.equals("Cornell") ? width * .29f : 65, height - 35, paint);
                if (page.paper.equals("Cornell")) canvas.drawLine(44, height * .81f, width - 44, height * .81f, paint);
            }
            case "Grid", "Grid small", "Grid large" -> {
                int step = switch (page.paper) { case "Grid small" -> 15; case "Grid large" -> 38; default -> 25; };
                for (int x = step; x < width; x += step) canvas.drawLine(x, 0, x, height, paint);
                for (int y = step; y < height; y += step) canvas.drawLine(0, y, width, y, paint);
            }
            case "Dotted", "Infinite" -> {
                android.graphics.Rect clip = canvas.getClipBounds();
                int step = 28 * Math.max(1, (Math.max(clip.width(), clip.height()) + 4200) / 4200);
                int left = Math.floorDiv(clip.left - 14, step) * step;
                int top = Math.floorDiv(clip.top - 14, step) * step;
                int right = page.infinite() ? clip.right : Math.min(width, clip.right);
                int bottom = page.infinite() ? clip.bottom : Math.min(height, clip.bottom);
                for (int x = left + 14; x < right; x += step) {
                    for (int y = top + 14; y < bottom; y += step) {
                        canvas.drawCircle(x, y, 1.45f, paint);
                    }
                }
            }
            case "Planner" -> {
                heading(canvas, "Day planner", 44, 79, 38);
                heading(canvas, "PRIORITIES", 44, 153, 17);
                heading(canvas, "SCHEDULE", 44, 370, 17);
                heading(canvas, "NOTES", width * .57f, 370, 17);
                paint.setColor(0xffd8e3df);
                for (int y = 200; y <= 320; y += 54) canvas.drawLine(44, y, width - 44, y, paint);
                for (int y = 420; y < height - 26; y += 53) canvas.drawLine(44, y, width - 44, y, paint);
                canvas.drawLine(width * .54f, 352, width * .54f, height - 45, paint);
            }
            case "Music" -> {
                for (int base = 90; base < height - 50; base += 125) {
                    for (int line = 0; line < 5; line++) {
                        canvas.drawLine(46, base + line * 11, width - 46, base + line * 11, paint);
                    }
                }
            }
        }
    }

    static boolean darkPaper(Notebook.Page page) {
        return page.paperColor != 0 && !PlannerTemplates.isPlanner(page.paper)
                && android.graphics.Color.luminance(page.paperColor) < .22f;
    }

    RectF printBounds(Notebook.Page page) {
        if (!page.infinite()) return new RectF(0, 0, page.width, page.height);
        RectF area = null;
        for (Notebook.Mark mark : page.marks) {
            if (mark.points.isEmpty() || !page.layer(mark.layer).visible) continue;
            RectF bound = bounds(mark);
            float margin = Math.max(12, mark.thickness * (mark.tool.equals("Arrow") ? 4 : 1));
            bound.inset(-margin, -margin);
            if (area == null) area = bound;
            else area.union(bound);
        }
        if (area == null) return new RectF(0, 0, 840, 1120);
        area.inset(-36, -36);
        return area;
    }

    private void heading(Canvas canvas, String label, float x, float y, float size) {
        type.setColor(0xff235750);
        type.setTextSize(size);
        canvas.drawText(label, x, y, type);
    }

    void drawMark(Canvas canvas, Notebook.Mark mark, Images images) {
        if (mark.points.isEmpty()) return;
        Notebook.Point first = mark.points.get(0);
        Notebook.Point last = mark.points.get(mark.points.size() - 1);
        float x = first.x();
        float y = first.y();
        paint.setColor(mark.color);
        paint.setAlpha(paint.getAlpha() * mark.opacity / 100);
        paint.setStrokeWidth(mark.thickness);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        switch (mark.tool) {
            case "Text", "Link", "Sticker" -> {
                type.setColor(mark.color);
                type.setTextSize(mark.thickness);
                type.setUnderlineText(mark.tool.equals("Link"));
                canvas.save();
                canvas.translate(x, y);
                text(mark).draw(canvas);
                canvas.restore();
                type.setUnderlineText(false);
            }
            case "Image" -> {
                Bitmap image = images.get(mark.value);
                if (image != null) {
                    paint.setColor(Color.WHITE);
                    canvas.drawBitmap(image, null, bounds(mark), paint);
                }
            }
            case "Rectangle" -> canvas.drawRect(bounds(mark), paint);
            case "Ellipse" -> canvas.drawOval(bounds(mark), paint);
            case "Line" -> canvas.drawLine(x, y, last.x(), last.y(), paint);
            case "Arrow" -> {
                canvas.drawLine(x, y, last.x(), last.y(), paint);
                double a = Math.atan2(last.y() - y, last.x() - x);
                float arm = Math.max(15, mark.thickness * 4);
                canvas.drawLine(last.x(), last.y(), last.x() - (float) Math.cos(a - .55) * arm,
                        last.y() - (float) Math.sin(a - .55) * arm, paint);
                canvas.drawLine(last.x(), last.y(), last.x() - (float) Math.cos(a + .55) * arm,
                        last.y() - (float) Math.sin(a + .55) * arm, paint);
            }
            case "Table" -> {
                RectF box = bounds(mark);
                canvas.drawRect(box, paint);
                for (int line = 1; line < 4; line++) {
                    float row = box.top + box.height() * line / 4;
                    canvas.drawLine(box.left, row, box.right, row, paint);
                }
                for (int line = 1; line < 3; line++) {
                    float column = box.left + box.width() * line / 3;
                    canvas.drawLine(column, box.top, column, box.bottom, paint);
                }
            }
            case "Tape" -> {
                if (mark.revealed) paint.setColor(0x27768173);
                else paint.setColor(0xff657d6d);
                drawPath(canvas, mark, false);
            }
            case Brushes.HIGHLIGHTER, Brushes.CHISEL -> {
                paint.setAlpha(100 * mark.opacity / 100);
                if (mark.tool.equals(Brushes.CHISEL)) paint.setStrokeCap(Paint.Cap.SQUARE);
                drawPath(canvas, mark, false);
            }
            case Brushes.BALLPOINT, Brushes.FINELINER, Brushes.FELT, Brushes.MARKER -> {
                if (mark.tool.equals(Brushes.FINELINER) || mark.tool.equals(Brushes.MARKER)) {
                    paint.setStrokeCap(Paint.Cap.SQUARE);
                }
                int alpha = switch (mark.tool) {
                    case Brushes.BALLPOINT -> 220;
                    case Brushes.FELT -> 195;
                    case Brushes.MARKER -> 175;
                    default -> 255;
                };
                paint.setAlpha(alpha * mark.opacity / 100);
                if (mark.tool.equals(Brushes.FINELINER)) paint.setStrokeWidth(mark.thickness * .72f);
                drawPath(canvas, mark, false);
            }
            case Brushes.CHARCOAL, Brushes.PASTEL -> drawDryPigment(canvas, mark);
            case Brushes.WATERCOLOR -> drawWash(canvas, mark);
            default -> drawPath(canvas, mark, Brushes.isInk(mark.tool));
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawWash(Canvas canvas, Notebook.Mark mark) {
        // Nested transparent strokes soften the edge without a raster brush texture.
        for (int ring = 3; ring > 0; ring--) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setAlpha((10 + (3 - ring) * 18) * mark.opacity / 100);
            paint.setStrokeWidth(mark.thickness * (.46f + .18f * ring));
            drawPath(canvas, mark, false);
        }
    }

    private void drawDryPigment(Canvas canvas, Notebook.Mark mark) {
        boolean pastel = mark.tool.equals(Brushes.PASTEL);
        paint.setAlpha((pastel ? 70 : 44) * mark.opacity / 100);
        paint.setStrokeWidth(mark.thickness * (pastel ? .75f : .56f));
        drawPath(canvas, mark, false);
        android.graphics.Path grains = new android.graphics.Path();
        float spacing = Math.max(1.4f, mark.thickness * (pastel ? .22f : .16f));
        float distanceToNext = 0;
        int grain = 0;
        Notebook.Point from = mark.points.get(0);
        for (Notebook.Point to : mark.points) {
            float dx = to.x() - from.x(), dy = to.y() - from.y();
            float length = (float) Math.hypot(dx, dy);
            if (length == 0) continue;
            for (; distanceToNext < length && grain < 2400; distanceToNext += spacing, grain++) {
                float t = distanceToNext / length;
                float pressure = Math.max(.18f, Math.min(1.25f, from.pressure() + (to.pressure() - from.pressure()) * t));
                float jitter = ((grain * 53 % 97) / 96f - .5f) * mark.thickness * (.6f + .4f * pressure);
                float radius = spacing * (pastel ? .34f : .21f) * (.65f + (grain * 29 % 47) / 47f);
                grains.addCircle(from.x() + dx * t - dy / length * jitter,
                        from.y() + dy * t + dx / length * jitter, radius, android.graphics.Path.Direction.CW);
            }
            if (grain >= 2400) break;
            distanceToNext -= length;
            from = to;
        }
        paint.setAlpha((pastel ? 125 : 160) * mark.opacity / 100);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(grains, paint);
    }

    private StaticLayout text(Notebook.Mark mark) {
        type.setTypeface(switch (mark.font) {
            case "serif" -> Typeface.SERIF;
            case "monospace" -> Typeface.MONOSPACE;
            case "cursive" -> CURSIVE;
            case "sans-serif-condensed" -> CONDENSED;
            default -> Typeface.DEFAULT;
        });
        return StaticLayout.Builder.obtain(mark.value, 0, mark.value.length(), type,
                        Math.max(140, Math.min(650, (int) Math.ceil(type.measureText(mark.value)))))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
    }

    private void drawPath(Canvas canvas, Notebook.Mark mark, boolean pressure) {
        List<Notebook.Point> points = mark.points;
        if (points.size() == 1) {
            paint.setStyle(Paint.Style.FILL);
            Notebook.Point point = points.get(0);
            float size = pressure ? Brushes.pressureWidth(mark, point, point) : paint.getStrokeWidth();
            canvas.drawCircle(point.x(), point.y(), size * .5f, paint);
            return;
        }
        if (!pressure) {
            android.graphics.Path path = new android.graphics.Path();
            Notebook.Point origin = points.get(0);
            path.moveTo(origin.x(), origin.y());
            boolean moved = false;
            for (int i = 1; i < points.size(); i++) {
                Notebook.Point point = points.get(i);
                moved |= point.x() != origin.x() || point.y() != origin.y();
                path.lineTo(point.x(), point.y());
            }
            if (moved) canvas.drawPath(path, paint);
            else {
                paint.setStyle(Paint.Style.FILL);
                float radius = paint.getStrokeWidth() * .5f;
                canvas.drawCircle(origin.x(), origin.y(), radius, paint);
            }
            return;
        }
        Notebook.Point a = points.get(0);
        boolean drawn = false;
        for (int i = 1; i < points.size(); i++) {
            Notebook.Point b = points.get(i);
            if (a.x() != b.x() || a.y() != b.y()) {
                paint.setStrokeWidth(Brushes.pressureWidth(mark, a, b));
                canvas.drawLine(a.x(), a.y(), b.x(), b.y(), paint);
                drawn = true;
            }
            a = b;
        }
        if (!drawn) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(a.x(), a.y(), Brushes.pressureWidth(mark, a, a) * .5f, paint);
        }
    }

    RectF bounds(Notebook.Mark mark) {
        if (mark.points.isEmpty()) return new RectF();
        Notebook.Point first = mark.points.get(0);
        if (Arrays.asList("Text", "Link", "Sticker").contains(mark.tool)) {
            type.setTextSize(mark.thickness);
            StaticLayout lines = text(mark);
            float width = 0;
            for (int line = 0; line < lines.getLineCount(); line++) width = Math.max(width, lines.getLineWidth(line));
            return new RectF(first.x(), first.y(), first.x() + width, first.y() + lines.getHeight());
        }
        float left = first.x(), top = first.y(), right = left, bottom = top;
        for (Notebook.Point point : mark.points) {
            left = Math.min(left, point.x());
            top = Math.min(top, point.y());
            right = Math.max(right, point.x());
            bottom = Math.max(bottom, point.y());
        }
        return new RectF(left, top, right, bottom);
    }

    boolean hit(Notebook.Mark mark, float x, float y, float tolerance) {
        if (mark.points.isEmpty()) return false;
        RectF box = bounds(mark);
        box.inset(-tolerance, -tolerance);
        if (!box.contains(x, y)) return false;
        if (Arrays.asList("Text", "Image", "Link", "Sticker", "Ellipse", "Rectangle", "Table").contains(mark.tool)) return true;
        double range = Math.max(tolerance, mark.thickness / 2);
        Notebook.Point previous = mark.points.get(0);
        for (Notebook.Point point : mark.points) {
            float dx = point.x() - previous.x(), dy = point.y() - previous.y();
            float squared = dx * dx + dy * dy;
            float t = squared == 0 ? 0 : Math.max(0, Math.min(1,
                    ((x - previous.x()) * dx + (y - previous.y()) * dy) / squared));
            if (Math.hypot(x - previous.x() - t * dx, y - previous.y() - t * dy) <= range) return true;
            previous = point;
        }
        return false;
    }
}
