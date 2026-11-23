package app.starrnote.android;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.summarization.Summarization;
import com.google.mlkit.genai.summarization.SummarizationRequest;
import com.google.mlkit.genai.summarization.Summarizer;
import com.google.mlkit.genai.summarization.SummarizerOptions;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

final class NotebookAudio {
    private static final int RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private final AudioRecord recorder;
    private final Thread worker;
    private volatile boolean running = true;

    @SuppressLint("MissingPermission")
    NotebookAudio(File path, Consumer<Exception> done) {
        int buffer = Math.max(8192, AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING));
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING, buffer);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("Microphone is unavailable");
        }
        try {
            recorder.startRecording();
        } catch (RuntimeException failure) {
            recorder.release();
            throw failure;
        }
        worker = new Thread(() -> capture(path, buffer, done), "StarrNote recorder");
        worker.start();
    }

    void stop() {
        running = false;
    }

    private void capture(File path, int size, Consumer<Exception> done) {
        Exception failure = null;
        try (RandomAccessFile file = new RandomAccessFile(path, "rw")) {
            byte[] bytes = new byte[size];
            wavHeader(file, 0);
            long written = 0;
            while (running) {
                int count = recorder.read(bytes, 0, bytes.length);
                if (count < 0) throw new IOException("Microphone recording interrupted");
                if (written + count > 0xffffffd0L) break;
                file.write(bytes, 0, count);
                written += count;
            }
            if (written == 0) throw new IOException("Recording was empty");
            file.seek(0);
            wavHeader(file, (int) written);
        } catch (Exception exception) {
            failure = exception;
        } finally {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) {
                // Recording may already have stopped if the audio input was interrupted.
            }
            recorder.release();
        }
        done.accept(failure);
    }

    private static void wavHeader(RandomAccessFile file, int data) throws IOException {
        file.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        file.writeInt(Integer.reverseBytes(data + 36));
        file.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        file.writeInt(Integer.reverseBytes(16));
        file.writeShort(Short.reverseBytes((short) 1));
        file.writeShort(Short.reverseBytes((short) 1));
        file.writeInt(Integer.reverseBytes(RATE));
        file.writeInt(Integer.reverseBytes(RATE * 2));
        file.writeShort(Short.reverseBytes((short) 2));
        file.writeShort(Short.reverseBytes((short) 16));
        file.write("data".getBytes(StandardCharsets.US_ASCII));
        file.writeInt(Integer.reverseBytes(data));
    }

    static Runnable transcribe(Context context, File file, Consumer<String> result, Consumer<String> error)
            throws IOException {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw new IOException("This device lacks an on-device speech recognition service");
        }
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        SpeechRecognizer recognizer;
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
        } catch (RuntimeException failure) {
            pipe[0].close();
            pipe[1].close();
            throw failure;
        }
        StringBuilder text = new StringBuilder();
        boolean[] closed = {false};
        Runnable close = () -> {
            if (closed[0]) return;
            closed[0] = true;
            recognizer.destroy();
            try {
                pipe[0].close();
            } catch (IOException ignored) {
                // Closing the reader also releases the writer if the service disconnected.
            }
        };
        recognizer.setRecognitionListener(new RecognitionListener() {
            private String value(Bundle data) {
                List<String> options = data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                return options == null || options.isEmpty() ? "" : options.get(0);
            }

            private void deliver(String message) {
                if (closed[0]) return;
                close.run();
                if (message.trim().isEmpty()) error.accept("No speech was recognized");
                else result.accept(message.trim());
            }

            @Override public void onSegmentResults(Bundle data) {
                if (text.length() > 0) text.append(". ");
                text.append(value(data));
            }
            @Override public void onEndOfSegmentedSession() { deliver(text.toString()); }
            @Override public void onResults(Bundle data) { deliver(text.length() == 0 ? value(data) : text.toString()); }
            @Override public void onError(int code) {
                if (closed[0]) return;
                if (text.length() > 0) deliver(text.toString());
                else {
                    close.run();
                    error.accept("On-device transcription is unavailable for this recording (" + code + ")");
                }
            }
            @Override public void onReadyForSpeech(Bundle b) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle b) {}
            @Override public void onEvent(int type, Bundle b) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, ENCODING)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        try {
            recognizer.startListening(intent);
        } catch (RuntimeException failure) {
            close.run();
            pipe[1].close();
            throw failure;
        }
        new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
                 FileInputStream input = new FileInputStream(file)) {
                input.getChannel().position(44);
                Library.copy(input, output, 0xffffffffL);
            } catch (IOException ignored) {
                // The service may close the stream after cancellation or an error.
            }
        }, "StarrNote audio pipeline").start();
        return close;
    }

    static String summarize(String transcript) {
        BreakIterator segments = BreakIterator.getSentenceInstance();
        segments.setText(transcript);
        List<String> sentences = new ArrayList<>();
        Map<String, Integer> frequency = new HashMap<>();
        for (int start = segments.first(), end = segments.next(); end != BreakIterator.DONE; start = end, end = segments.next()) {
            String sentence = transcript.substring(start, end).trim();
            if (sentence.isEmpty()) continue;
            sentences.add(sentence);
            for (String word : keywords(sentence)) frequency.merge(word, 1, Integer::sum);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> score(sentences.get(i), frequency)).reversed());
        order = new ArrayList<>(order.subList(0, Math.min(5, order.size())));
        order.sort(Integer::compare);
        StringBuilder result = new StringBuilder();
        for (int i : order) {
            if (result.length() > 0) result.append('\n');
            result.append("• ").append(sentences.get(i));
        }
        return result.toString();
    }

    static String generativeSummary(Context context, String transcript, int language) throws Exception {
        if (transcript.length() < 401) throw new IOException("AI summarization requires at least 401 characters");
        if (transcript.length() > 60000) throw new IOException("Choose a shorter transcript section or use Extract key points");
        int modelLanguage = switch (language) {
            case 1 -> SummarizerOptions.Language.JAPANESE;
            case 2 -> SummarizerOptions.Language.KOREAN;
            default -> SummarizerOptions.Language.ENGLISH;
        };
        Summarizer summarizer = Summarization.getClient(SummarizerOptions.builder(context)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(modelLanguage).setLongInputAutoTruncationEnabled(true).build());
        try {
            if (summarizer.checkFeatureStatus().get(20, TimeUnit.SECONDS) == FeatureStatus.UNAVAILABLE) {
                throw new IOException("AI summaries require a device with a compatible Android AICore model");
            }
            List<String> chunks = new ArrayList<>();
            int cursor = 0;
            while (cursor < transcript.length()) {
                int boundary = Math.min(transcript.length(), cursor + 3700);
                if (transcript.length() - boundary < 450) boundary = transcript.length();
                if (boundary < transcript.length()) {
                    int split = transcript.lastIndexOf(' ', boundary);
                    if (split > cursor + 900) boundary = split + 1;
                    if (Character.isLowSurrogate(transcript.charAt(boundary))) boundary--;
                }
                chunks.add(summarizer.runInference(SummarizationRequest.builder(transcript.substring(cursor, boundary))
                        .build()).get(120, TimeUnit.SECONDS).getSummary());
                cursor = boundary;
            }
            return String.join("\n\n", chunks);
        } finally {
            summarizer.close();
        }
    }

    private static String[] keywords(String sentence) { return sentence.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"); }

    private static double score(String sentence, Map<String, Integer> frequency) {
        double score = 0;
        String[] words = keywords(sentence);
        for (String word : words) if (word.length() > 3) score += frequency.getOrDefault(word, 0);
        return score / Math.sqrt(Math.max(1, words.length));
    }
}
