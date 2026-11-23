package app.starrnote.android;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer;
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.recognition.Ink;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Recognition {
    static final String[] LANGUAGES = {"English", "Arabic", "Chinese", "French", "German", "Italian",
            "Japanese", "Korean", "Portuguese", "Russian", "Spanish"};
    private static final String[] TAGS = {"en-US", "ar", "zh-Hani", "fr", "de", "it",
            "ja", "ko", "pt", "ru", "es"};

    static Task<String> handwriting(List<Notebook.Mark> marks, int language) {
        try {
            DigitalInkRecognitionModelIdentifier id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(TAGS[language]);
            if (id == null) return Tasks.forException(new IllegalStateException("Language model unavailable"));
            DigitalInkRecognitionModel model = DigitalInkRecognitionModel.builder(id).build();
            List<Line> lines = lines(marks);
            if (lines.isEmpty()) return Tasks.forResult("");
            return RemoteModelManager.getInstance().download(model, new DownloadConditions.Builder().build())
                    .onSuccessTask(done -> {
                        DigitalInkRecognizer client = DigitalInkRecognition.getClient(
                                DigitalInkRecognizerOptions.builder(model).build());
                        List<Task<String>> results = new ArrayList<>();
                        for (Line line : lines) {
                            Ink.Builder ink = Ink.builder();
                            for (Notebook.Mark mark : line.marks) {
                                Ink.Stroke.Builder stroke = Ink.Stroke.builder();
                                for (Notebook.Point point : mark.points) stroke.addPoint(Ink.Point.create(point.x(), point.y()));
                                ink.addStroke(stroke.build());
                            }
                            results.add(client.recognize(ink.build()).onSuccessTask(result -> Tasks.forResult(
                                    result.getCandidates().isEmpty() ? "" : result.getCandidates().get(0).getText())));
                        }
                        return Tasks.whenAllSuccess(results).continueWith(task -> {
                            client.close();
                            StringBuilder recognized = new StringBuilder();
                            for (Object line : task.getResult()) {
                                if (line.toString().trim().isEmpty()) continue;
                                if (recognized.length() > 0) recognized.append('\n');
                                recognized.append(line);
                            }
                            return recognized.toString();
                        });
                    });
        } catch (Exception failure) {
            return Tasks.forException(failure);
        }
    }

    private static List<Line> lines(List<Notebook.Mark> marks) {
        List<Line> lines = new ArrayList<>();
        Paper geometry = new Paper();
        for (Notebook.Mark mark : marks) {
            if (mark.points.isEmpty() || !Brushes.isInk(mark.tool)) continue;
            float center = geometry.bounds(mark).centerY();
            Line closest = null;
            float distance = 33;
            for (Line line : lines) {
                float delta = Math.abs(line.center() - center);
                if (delta < distance) {
                    distance = delta;
                    closest = line;
                }
            }
            if (closest == null) {
                closest = new Line();
                lines.add(closest);
            }
            closest.marks.add(mark);
            closest.total += center;
        }
        lines.sort(Comparator.comparingDouble(Line::center));
        return lines;
    }

    static Task<String> image(Bitmap bitmap) {
        TextRecognizer client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        return client.process(InputImage.fromBitmap(bitmap, 0)).continueWith(task -> {
            client.close();
            return task.getResult().getText();
        });
    }

    private static final class Line {
        final List<Notebook.Mark> marks = new ArrayList<>();
        float total;
        float center() { return total / marks.size(); }
    }
}
