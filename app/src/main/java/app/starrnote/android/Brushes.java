package app.starrnote.android;

final class Brushes {
    static final String PEN = "Pen";
    static final String FOUNTAIN = "Fountain pen";
    static final String CALLIGRAPHY = "Calligraphy pen";
    static final String PENCIL = "Pencil";
    static final String HIGHLIGHTER = "Highlighter";
    static final String CHISEL = "Chisel highlighter";
    static final String BALLPOINT = "Ballpoint pen";
    static final String GEL = "Gel pen";
    static final String BRUSH = "Brush pen";
    static final String FINELINER = "Fineliner";
    static final String FELT = "Felt-tip pen";
    static final String MECHANICAL = "Mechanical pencil";
    static final String CHARCOAL = "Charcoal";
    static final String PASTEL = "Pastel";
    static final String MARKER = "Broad marker";
    static final String WATERCOLOR = "Watercolor";

    static final String[] PENS = {FOUNTAIN, CALLIGRAPHY, PEN, GEL, BALLPOINT, BRUSH, FINELINER, FELT};
    static final String[] PENCILS = {PENCIL, MECHANICAL, CHARCOAL, PASTEL};
    static final String[] MARKERS = {HIGHLIGHTER, CHISEL, MARKER, WATERCOLOR};

    private Brushes() {}

    static boolean isPen(String tool) {
        return switch (tool) {
            case PEN, FOUNTAIN, CALLIGRAPHY, BALLPOINT, GEL, BRUSH, FINELINER, FELT -> true;
            default -> false;
        };
    }

    static boolean isPencil(String tool) {
        return PENCIL.equals(tool) || MECHANICAL.equals(tool) || CHARCOAL.equals(tool) || PASTEL.equals(tool);
    }

    static boolean isMarker(String tool) {
        return isHighlighter(tool) || MARKER.equals(tool) || WATERCOLOR.equals(tool);
    }

    static boolean isHighlighter(String tool) {
        return HIGHLIGHTER.equals(tool) || CHISEL.equals(tool);
    }

    static boolean isInk(String tool) { return isPen(tool) || isPencil(tool); }

    static boolean adjustablePressure(String tool) { return FOUNTAIN.equals(tool) || CALLIGRAPHY.equals(tool); }

    static final float PAPER_UNITS_PER_MM = 4f;

    static float width(String tool) {
        if (isHighlighter(tool)) return 24;
        return switch (tool) {
            case FOUNTAIN -> .65f * PAPER_UNITS_PER_MM;
            case CALLIGRAPHY -> .50f * PAPER_UNITS_PER_MM;
            case PENCIL -> 3;
            case BALLPOINT -> 3;
            case GEL -> 5;
            case BRUSH -> 14;
            case FINELINER, MECHANICAL -> 2;
            case FELT -> 8;
            case CHARCOAL -> 16;
            case PASTEL -> 24;
            case MARKER -> 28;
            case WATERCOLOR -> 40;
            default -> 5;
        };
    }

    static float pressureWidth(Notebook.Mark mark, Notebook.Point from, Notebook.Point to) {
        float pressure = Math.max(.15f, Math.min(1.25f, (from.pressure() + to.pressure()) * .5f));
        if (adjustablePressure(mark.tool) && mark.pressureSensitivity < 100) {
            pressure = .65f + (pressure - .65f) * mark.pressureSensitivity / 100f;
        }
        float multiplier = switch (mark.tool) {
            case FOUNTAIN -> .20f + 1.12f * pressure;
            case BRUSH -> .14f + 1.34f * pressure * pressure;
            case GEL -> .85f + .15f * pressure;
            case MECHANICAL -> .66f + .18f * pressure;
            case CALLIGRAPHY -> {
                double dx = to.x() - from.x(), dy = to.y() - from.y();
                double length = Math.hypot(dx, dy);
                // Project a 35-degree broad nib onto the normal of the stroke.
                double nib = length == 0 ? 1 : Math.abs(.574 * dx - .819 * dy) / length;
                yield (.28f + .92f * pressure) * (.19f + .81f * (float) nib);
            }
            default -> .55f + .82f * pressure;
        };
        return mark.thickness * multiplier;
    }
}
