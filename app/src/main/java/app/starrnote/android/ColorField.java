package app.starrnote.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import java.util.function.IntConsumer;

/** Selects saturation and brightness independently at the current hue. */
final class ColorField extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private final IntConsumer changed;
    private Shader saturation, brightness;

    ColorField(Context context, int color, IntConsumer changed) {
        super(context);
        Color.colorToHSV(color, hsv);
        this.changed = changed;
        setContentDescription("Saturation and brightness palette");
    }

    int hue() { return Math.round(hsv[0]); }

    void hue(int hue) {
        hsv[0] = hue % 360;
        shaders();
        notifyColor();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { shaders(); }

    private void shaders() {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        saturation = new LinearGradient(0, 0, getWidth(), 0, Color.WHITE,
                Color.HSVToColor(new float[]{hsv[0], 1, 1}), Shader.TileMode.CLAMP);
        brightness = new LinearGradient(0, 0, 0, getHeight(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(saturation);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(brightness);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
        float x = hsv[1] * (getWidth() - 12) + 6, y = (1 - hsv[2]) * (getHeight() - 12) + 6;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(4);
        canvas.drawCircle(x, y, 5, paint);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(2);
        canvas.drawCircle(x, y, 5, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                getParent().requestDisallowInterceptTouchEvent(true);
                hsv[1] = Math.max(0, Math.min(1, event.getX() / getWidth()));
                hsv[2] = 1 - Math.max(0, Math.min(1, event.getY() / getHeight()));
                notifyColor();
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            }
            default -> { return false; }
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void notifyColor() {
        invalidate();
        changed.accept(Color.HSVToColor(hsv));
    }
}
