package app.starrnote.android;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Original 24-unit line icons for the editor. */
final class ToolIcon extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final String name;
    private final int size;

    ToolIcon(String name, int color, int size) {
        this.name = name;
        this.size = size;
        paint.setColor(color);
        paint.setStrokeWidth(1.65f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);
    }

    @Override public void draw(Canvas c) {
        Rect bounds = getBounds();
        float scale = Math.min(bounds.width(), bounds.height()) / 24f;
        c.save();
        c.translate(bounds.exactCenterX() - 12 * scale, bounds.exactCenterY() - 12 * scale);
        c.scale(scale, scale);
        switch (name) {
            case "Close" -> { lines(c, 6, 6, 18, 18); lines(c, 18, 6, 6, 18); }
            case "Check" -> lines(c, 4, 12, 9, 17, 20, 6);
            case "Back" -> lines(c, 14, 5, 7, 12, 14, 19);
            case "Forward" -> lines(c, 10, 5, 17, 12, 10, 19);
            case "Pages" -> {
                c.drawRoundRect(3, 4, 21, 20, 1.5f, 1.5f, paint);
                lines(c, 9, 4, 9, 20);
                lines(c, 5, 8, 7, 8);
                lines(c, 5, 12, 7, 12);
            }
            case "Page turning" -> {
                lines(c, 4, 8, 20, 8, 16, 4);
                lines(c, 20, 8, 16, 12);
                lines(c, 20, 16, 4, 16, 8, 12);
                lines(c, 4, 16, 8, 20);
            }
            case "Add page", "Import pages", "Page template" -> {
                lines(c, 14, 3, 6, 3, 6, 21, 19, 21, 19, 8, 14, 3, 14, 8, 19, 8);
                if (name.equals("Add page")) {
                    lines(c, 10, 14, 15, 14);
                    lines(c, 12.5f, 11.5f, 12.5f, 16.5f);
                } else if (name.equals("Import pages")) {
                    lines(c, 12.5f, 10.5f, 12.5f, 17, 9.5f, 14);
                    lines(c, 12.5f, 17, 15.5f, 14);
                } else {
                    lines(c, 9, 12, 16, 12);
                    lines(c, 9, 16, 16, 16);
                }
            }
            case "Copy page", "Duplicate page" -> {
                c.drawRoundRect(8, 7, 20, 21, 1.5f, 1.5f, paint);
                lines(c, 4, 17, 4, 3, 16, 3);
                if (name.equals("Duplicate page")) {
                    lines(c, 11, 14, 17, 14);
                    lines(c, 14, 11, 14, 17);
                }
            }
            case "Paste page" -> {
                lines(c, 8, 5, 5, 5, 5, 21, 19, 21, 19, 5, 16, 5);
                c.drawRoundRect(8, 3, 16, 7, 1.5f, 1.5f, paint);
                lines(c, 9, 12, 15, 12);
                lines(c, 9, 16, 15, 16);
            }
            case "Move page earlier", "Move page later" -> {
                if (name.equals("Move page earlier")) {
                    c.translate(0, 24);
                    c.scale(1, -1);
                }
                lines(c, 12, 4, 12, 19, 7, 14);
                lines(c, 12, 19, 17, 14);
            }
            case "Delete page" -> {
                lines(c, 4, 6, 20, 6);
                lines(c, 9, 6, 9, 3, 15, 3, 15, 6);
                lines(c, 6, 6, 7, 21, 17, 21, 18, 6);
                lines(c, 10, 10, 10, 17);
                lines(c, 14, 10, 14, 17);
            }
            case Brushes.PEN, Brushes.BALLPOINT, Brushes.GEL, Brushes.FINELINER, Brushes.FELT ->
                    lines(c, 4, 20, 6, 14, 16, 4, 20, 8, 10, 18, 4, 20, 6, 14, 10, 18);
            case Brushes.FOUNTAIN -> {
                lines(c, 4, 20, 6, 10, 17, 4, 20, 7, 14, 18, 4, 20, 11, 13);
                c.drawCircle(12, 12, 1.35f, paint);
            }
            case Brushes.CALLIGRAPHY, Brushes.BRUSH -> lines(c, 4, 20, 11, 17, 20, 8, 15, 3, 6, 12, 4, 20, 6, 12, 11, 17);
            case Brushes.PENCIL, Brushes.MECHANICAL, Brushes.CHARCOAL, Brushes.PASTEL -> {
                lines(c, 4, 20, 5, 15, 16, 4, 20, 8, 9, 19, 4, 20, 5, 15, 9, 19);
                lines(c, 14, 6, 18, 10);
            }
            case Brushes.HIGHLIGHTER, Brushes.CHISEL, Brushes.MARKER, Brushes.WATERCOLOR -> {
                lines(c, 7, 14, 16, 4, 20, 8, 11, 18, 7, 14, 5, 18, 8, 20, 11, 18);
                lines(c, 4, 21, 19, 21);
            }
            case "Eraser" -> lines(c, 3, 14, 13, 4, 21, 12, 13, 20, 9, 20, 3, 14, 9, 8, 17, 16, 13, 20, 21, 20);
            case "Lasso" -> {
                c.drawOval(4, 5, 20, 17, paint);
                path.reset();
                path.moveTo(11, 17);
                path.cubicTo(9, 14, 6, 17, 9, 20);
                path.cubicTo(12, 22, 15, 19, 14, 17);
                c.drawPath(path, paint);
            }
            case "Tools" -> {
                c.drawCircle(5, 12, 1, paint);
                c.drawCircle(12, 12, 1, paint);
                c.drawCircle(19, 12, 1, paint);
            }
            case "Undo", "Redo" -> {
                if (name.equals("Redo")) { c.translate(24, 0); c.scale(-1, 1); }
                lines(c, 9, 5, 4, 10, 9, 15);
                path.reset();
                path.moveTo(4, 10);
                path.lineTo(14, 10);
                path.cubicTo(22, 10, 22, 20, 13, 20);
                c.drawPath(path, paint);
            }
            case "Zoom out", "Zoom in" -> {
                lines(c, 5, 12, 19, 12);
                if (name.equals("Zoom in")) lines(c, 12, 5, 12, 19);
            }
            case "Image" -> {
                c.drawRoundRect(3, 4, 21, 20, 2, 2, paint);
                c.drawCircle(15.5f, 8.7f, 1.6f, paint);
                lines(c, 3, 17, 9, 11, 14, 16, 17, 13, 21, 17);
            }
            case "Layers" -> {
                lines(c, 3, 9, 12, 4, 21, 9, 12, 14, 3, 9);
                lines(c, 3, 13, 12, 18, 21, 13);
                lines(c, 3, 17, 12, 22, 21, 17);
            }
            case "Document grid" -> {
                for (int x : new int[]{4, 13}) for (int y : new int[]{4, 13}) c.drawRect(x, y, x + 7, y + 7, paint);
            }
            case "Pen box" -> lines(c, 12, 3, 14.8f, 8.8f, 21.2f, 9.7f, 16.6f, 14.2f,
                    17.7f, 20.6f, 12, 17.6f, 6.3f, 20.6f, 7.4f, 14.2f, 2.8f, 9.7f, 9.2f, 8.8f, 12, 3);
            case "Ink color" -> {
                lines(c, 12, 3, 5, 13, 5, 17, 8, 21, 16, 21, 19, 17, 19, 13, 12, 3);
                lines(c, 8, 15, 8, 17, 10, 19);
            }
            case "Study timer" -> {
                c.drawCircle(12, 13, 8, paint);
                lines(c, 10, 2, 14, 2);
                lines(c, 12, 9, 12, 14, 15, 15);
            }
            case "Audio recordings" -> {
                c.drawRoundRect(9, 3, 15, 15, 3, 3, paint);
                c.drawArc(5, 9, 19, 19, 0, 180, false, paint);
                lines(c, 12, 19, 12, 22, 8, 22, 16, 22);
            }
            case "Insert text" -> lines(c, 4, 7, 4, 4, 20, 4, 20, 7, 20, 4, 12, 4, 12, 20, 8, 20, 16, 20);
            case "Eraser settings" -> {
                lines(c, 4, 6, 20, 6);
                lines(c, 4, 12, 20, 12);
                lines(c, 4, 18, 20, 18);
                c.drawCircle(8, 6, 2, paint);
                c.drawCircle(15, 12, 2, paint);
                c.drawCircle(10, 18, 2, paint);
            }
            case "Hand" -> {
                path.reset();
                path.moveTo(7, 20);
                path.lineTo(3, 13);
                path.quadTo(3, 10, 6, 12);
                path.lineTo(8, 14);
                path.lineTo(8, 5);
                path.quadTo(9.5f, 2, 11, 5);
                path.lineTo(11, 10);
                path.quadTo(12.5f, 7, 14, 10);
                path.lineTo(14, 11);
                path.quadTo(15.5f, 8, 17, 11);
                path.lineTo(17, 12);
                path.quadTo(19, 10, 20, 13);
                path.lineTo(19, 17);
                path.quadTo(18, 21, 15, 21);
                path.lineTo(9, 21);
                path.close();
                c.drawPath(path, paint);
            }
        }
        c.restore();
    }

    private void lines(Canvas canvas, float... points) {
        path.reset();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        canvas.drawPath(path, paint);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
