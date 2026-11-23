package app.starrnote.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class StudioStyle {
    static final String[] NAMES = {"Juniper", "Midnight", "Terracotta", "Sage", "Custom", "Charcoal"};
    static final int CUSTOM = 4;
    static final int CHARCOAL = 5;
    private static final int[] CHARCOAL_COLORS =
            {0xff292929, 0xff202020, 0xffeeeeee, 0xffababab, 0xff282828, 0xff62bbd1, 0xff393939};
    private static final int[][] COLORS = {
            {0xfff4f3eb, 0xfffffef8, 0xff172e2a, 0xff526e62, 0xff174e46, 0xffddf39d, 0xffdde8db},
            {0xff182321, 0xff253532, 0xfff0f5e6, 0xffb3c6b6, 0xff102e29, 0xffddf39d, 0xff3a5147},
            {0xfffaf0e7, 0xfffffaf4, 0xff3d2b2a, 0xff79554d, 0xff713b39, 0xffffdab0, 0xffefd9cc},
            {0xffedf1e4, 0xfff8f9f1, 0xff263828, 0xff556d51, 0xff34543b, 0xffeef4b1, 0xffdce7d0}
    };

    final Context context;
    final int page, surface, ink, muted, primary, lime, line;
    final boolean charcoal;

    static boolean tabletWorkspace(Context context) {
        android.content.res.Configuration config = context.getResources().getConfiguration();
        return config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 840;
    }

    StudioStyle(Context context, int theme) {
        this(context, theme, 0xff244b55, 0xfff5e5a4);
    }

    StudioStyle(Context context, int theme, int customPrimary, int customAccent) {
        this.context = context;
        boolean custom = theme == CUSTOM;
        charcoal = theme == CHARCOAL;
        int[] c = charcoal ? CHARCOAL_COLORS
                : custom ? COLORS[0] : COLORS[Math.max(0, Math.min(theme, COLORS.length - 1))];
        page = c[0]; surface = c[1]; ink = c[2]; muted = c[3];
        primary = custom ? customPrimary : c[4];
        lime = custom ? customAccent : c[5];
        line = c[6];
    }

    int dp(float px) { return Math.round(px * context.getResources().getDisplayMetrics().density); }

    TextView text(CharSequence label, float size, int color) {
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    TextView title(String label, float size, int color) {
        TextView text = text(label, size, color);
        text.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        return text;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    GradientDrawable block(int color, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    RippleDrawable ripple(int color, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(0x24548473), block(color, radius, stroke),
                block(Color.WHITE, radius, 0));
    }

    TextView button(String label, boolean filled, Runnable click) {
        TextView button = text(label, 14, filled ? 0xff16352d : ink);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(13), 0, dp(13), 0);
        button.setMinHeight(dp(44));
        button.setGravity(Gravity.CENTER);
        button.setBackground(ripple(filled ? lime : surface, charcoal ? 7 : 14, filled ? 0 : line));
        button.setOnClickListener(v -> click.run());
        return button;
    }

    void pad(View view, int l, int t, int r, int b) {
        view.setPadding(dp(l), dp(t), dp(r), dp(b));
    }

    void append(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        parent.addView(child, params);
    }

    void chip(LinearLayout parent, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.rightMargin = dp(7);
        parent.addView(child, params);
    }
}
