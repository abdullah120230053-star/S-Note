package app.starrnote.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;

final class Exports {
    static void pdf(Library library, Notebook notebook, OutputStream output) throws IOException {
        Paper painter = new Paper();
        PdfDocument pdf = new PdfDocument();
        try {
            for (int i = 0; i < notebook.pages.size(); i++) {
                Notebook.Page page = notebook.pages.get(i);
                RectF bounds = painter.printBounds(page);
                float ratio = Math.min(1, 1600f / Math.max(bounds.width(), bounds.height()));
                PdfDocument.Page sheet = pdf.startPage(new PdfDocument.PageInfo.Builder(
                        Math.max(1, Math.round(bounds.width() * ratio)), Math.max(1, Math.round(bounds.height() * ratio)), i + 1)
                        .create());
                Bitmap background = library.background(page);
                try {
                    Canvas canvas = sheet.getCanvas();
                    canvas.scale(ratio, ratio);
                    canvas.translate(-bounds.left, -bounds.top);
                    painter.draw(canvas, page, background, library::image, true);
                } finally {
                    pdf.finishPage(sheet);
                    // PdfDocument can reference the background bitmap until writeTo completes.
                }
            }
            pdf.writeTo(output);
        } finally {
            pdf.close();
        }
    }

    static Bitmap page(Library library, Notebook.Page page) throws IOException {
        Paper painter = new Paper();
        RectF bounds = painter.printBounds(page);
        float ratio = Math.min(1.45f, 2200f / Math.max(bounds.width(), bounds.height()));
        Bitmap output = Bitmap.createBitmap(Math.max(1, Math.round(bounds.width() * ratio)),
                Math.max(1, Math.round(bounds.height() * ratio)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Bitmap background = library.background(page);
        try {
            canvas.scale(ratio, ratio);
            canvas.translate(-bounds.left, -bounds.top);
            painter.draw(canvas, page, background, library::image, true);
        } finally {
            if (background != null) background.recycle();
        }
        return output;
    }
}
