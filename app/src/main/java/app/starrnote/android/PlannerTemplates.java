package app.starrnote.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

final class PlannerTemplates {
    static final String DAILY_AGENDA = "Daily agenda", DAILY_FOCUS = "Daily focus";
    static final String WEEKLY_BOARDS = "Weekly boards", WEEKLY_TIMETABLE = "Weekly timetable";
    static final String TASK_CHECKLIST = "To-do checklist", TASK_BOARD = "To-do board";
    static final String LECTURE_REVIEW = "Lecture review", LAB_LOG = "Lab log", HABIT_TRACKER = "Habit tracker";
    static final String[] THEMES = {"Juniper", "Coast", "Terracotta", "Graphite"};
    private static final String[] DAYS = {"Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};
    private static final int[][] COLORS = {
            {0xff19483e, 0xff426e59, 0xffeff6e8, 0xffe6efe9, 0xffc0d4c3},
            {0xff163d63, 0xff3d678a, 0xffedf7fc, 0xffe8f1f9, 0xffc4d7e4},
            {0xff693928, 0xff925a45, 0xfffff0e3, 0xfff7e7de, 0xffe2c7b7},
            {0xff282b2d, 0xff484f56, 0xfff4f4f4, 0xffeceeef, 0xffcacecf}
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int ink, accent, wash, shade, rule;

    static boolean isPlanner(String paper) {
        return switch (paper) {
            case DAILY_AGENDA, DAILY_FOCUS, WEEKLY_BOARDS, WEEKLY_TIMETABLE, TASK_CHECKLIST, TASK_BOARD,
                    LECTURE_REVIEW, LAB_LOG, HABIT_TRACKER -> true;
            default -> false;
        };
    }

    boolean draw(Canvas canvas, Notebook.Page page) {
        if (!isPlanner(page.paper)) return false;
        int[] c = COLORS[Math.max(0, Math.min(page.paperTheme, COLORS.length - 1))];
        ink = c[0]; accent = c[1]; wash = c[2]; shade = c[3]; rule = c[4];
        canvas.save();
        canvas.scale(page.width / 840f, page.height / 1120f);
        label(canvas, page.paper, 44, 79, 42, ink, true);
        String date = page.paper.startsWith("Weekly") ? "WEEK OF" : "DATE";
        label(canvas, date, 46, 116, 13, accent, false);
        line(canvas, 130, 120, 430, 120, rule);
        line(canvas, 44, 135, 796, 135, rule);
        switch (page.paper) {
            case DAILY_AGENDA -> dailyAgenda(canvas);
            case DAILY_FOCUS -> dailyFocus(canvas);
            case WEEKLY_BOARDS -> weeklyBoards(canvas);
            case WEEKLY_TIMETABLE -> weeklyTimetable(canvas);
            case TASK_CHECKLIST -> checklist(canvas);
            case TASK_BOARD -> taskBoard(canvas);
            case LECTURE_REVIEW -> sections(canvas, "Key concepts", "Questions for review", "Summary");
            case LAB_LOG -> sections(canvas, "Objective and materials", "Procedure and observations", "Results and next steps");
            case HABIT_TRACKER -> habits(canvas);
        }
        canvas.restore();
        return true;
    }

    private void sections(Canvas canvas, String first, String second, String third) {
        String[] labels = {first, second, third};
        for (int section = 0; section < labels.length; section++) {
            int top = 158 + section * 302;
            panel(canvas, labels[section], 44, top, 752, 282, section % 2 == 0 ? wash : shade);
            rows(canvas, 62, top + 55, 775, 6, 35, false);
        }
    }

    private void habits(Canvas canvas) {
        panel(canvas, "Habit", 44, 158, 752, 665, wash);
        for (int day = 0; day < DAYS.length; day++) {
            label(canvas, DAYS[day].substring(0, 3), 334 + day * 63, 205, 14, accent, false);
        }
        for (int row = 0; row < 9; row++) {
            int y = 232 + row * 63;
            line(canvas, 62, y + 47, 775, y + 47, rule);
            for (int day = 0; day < DAYS.length; day++) {
                int x = 338 + day * 63;
                paint.setColor(rule);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(x, y, x + 22, y + 22, 3, 3, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        panel(canvas, "Reflections", 44, 846, 752, 210, shade);
        rows(canvas, 62, 900, 775, 4, 34, false);
    }

    private void dailyAgenda(Canvas c) {
        panel(c, "Schedule", 44, 157, 358, 914, wash);
        for (int hour = 6; hour <= 22; hour++) {
            float top = 203 + (hour - 6) * 50;
            label(c, hour + ":00", 60, top + 20, 15, accent, false);
            line(c, 56, top + 39, 388, top + 39, rule);
        }
        line(c, 129, 199, 129, 1057, rule);
        panel(c, "Top priorities", 418, 157, 378, 172, shade);
        rows(c, 432, 206, 782, 3, 33, true);
        panel(c, "To do", 418, 344, 378, 388, wash);
        rows(c, 432, 396, 782, 9, 34, true);
        panel(c, "Tomorrow", 418, 747, 378, 132, shade);
        rows(c, 434, 796, 780, 2, 28, false);
        panel(c, "Notes", 418, 894, 378, 177, wash);
        rows(c, 434, 942, 780, 3, 32, false);
    }

    private void dailyFocus(Canvas c) {
        panel(c, "Main goal", 44, 155, 752, 129, wash);
        rows(c, 62, 208, 778, 2, 29, false);
        panel(c, "Deep work", 44, 299, 369, 384, shade);
        panel(c, "Quick tasks", 427, 299, 369, 384, wash);
        for (int slot = 0; slot < 4; slot++) {
            int top = 347 + slot * 78;
            label(c, "Block " + (slot + 1), 62, top + 17, 15, accent, false);
            line(c, 148, top + 43, 397, top + 43, rule);
            line(c, 62, top + 63, 397, top + 63, rule);
        }
        rows(c, 444, 348, 780, 9, 33, true);
        panel(c, "Appointments", 44, 698, 752, 170, shade);
        rows(c, 62, 749, 778, 3, 32, false);
        panel(c, "Review and follow-up", 44, 883, 752, 187, wash);
        rows(c, 62, 933, 778, 3, 35, false);
    }

    private void weeklyBoards(Canvas c) {
        for (int day = 0; day < 8; day++) {
            float x = 44 + (day % 2) * 383, y = 158 + (day / 2) * 229;
            panel(c, day < 7 ? DAYS[day] : "Notes", x, y, 369, 213, day % 2 == 0 ? wash : shade);
            rows(c, x + 14, y + 48, x + 354, 4, 34, day < 7);
        }
    }

    private void weeklyTimetable(Canvas c) {
        panel(c, "Week at a glance", 44, 154, 752, 619, wash);
        for (int day = 0; day < DAYS.length; day++) {
            float x = 48 + day * 106;
            label(c, DAYS[day], x + 6, 226, 15, ink, false);
            if (day > 0) line(c, x, 202, x, 755, rule);
        }
        for (int row = 0; row <= 10; row++) line(c, 53, 245 + row * 50, 786, 245 + row * 50, rule);
        panel(c, "This week’s priorities", 44, 787, 369, 283, shade);
        rows(c, 58, 838, 397, 6, 33, true);
        panel(c, "Notes and deadlines", 427, 787, 369, 283, wash);
        rows(c, 445, 838, 780, 6, 33, false);
    }

    private void checklist(Canvas c) {
        panel(c, "Priority tasks", 44, 154, 752, 175, shade);
        rows(c, 62, 206, 779, 3, 32, true);
        panel(c, "Checklist", 44, 343, 752, 587, wash);
        label(c, "DUE", 657, 400, 13, accent, false);
        line(c, 631, 383, 631, 910, rule);
        rows(c, 62, 410, 780, 13, 38, true);
        panel(c, "Follow-up", 44, 944, 752, 126, shade);
        rows(c, 62, 992, 780, 2, 30, false);
    }

    private void taskBoard(Canvas c) {
        panel(c, "What matters today", 44, 154, 752, 133, shade);
        rows(c, 62, 201, 780, 2, 34, false);
        String[] columns = {"Next", "In progress", "Done"};
        for (int i = 0; i < columns.length; i++) {
            float x = 44 + i * 254;
            panel(c, columns[i], x, 303, 244, 600, i == 1 ? shade : wash);
            rows(c, x + 13, 353, x + 232, 12, 44, true);
        }
        panel(c, "Carry forward", 44, 919, 752, 151, shade);
        rows(c, 62, 971, 780, 2, 34, false);
    }

    private void panel(Canvas c, String title, float x, float y, float w, float h, int fill) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        c.drawRoundRect(x, y, x + w, y + h, 13, 13, paint);
        label(c, title, x + 15, y + 29, 19, ink, false);
        line(c, x + 13, y + 40, x + w - 13, y + 40, rule);
    }

    private void rows(Canvas canvas, float x, float y, float right, int count, float gap, boolean boxes) {
        for (int row = 0; row < count; row++) {
            float top = y + row * gap;
            if (boxes) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.4f);
                paint.setColor(accent);
                canvas.drawRoundRect(x + 3, top + 5, x + 17, top + 19, 3, 3, paint);
            }
            line(canvas, x + (boxes ? 31 : 0), top + gap - 4, right, top + gap - 4, rule);
        }
    }

    private void label(Canvas c, String text, float x, float y, float size, int color, boolean title) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTypeface(title ? Typeface.SERIF : Typeface.DEFAULT_BOLD);
        paint.setTextSize(size);
        c.drawText(text, x, y, paint);
    }

    private void line(Canvas c, float x, float y, float right, float bottom, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.05f);
        paint.setColor(color);
        c.drawLine(x, y, right, bottom, paint);
    }
}
