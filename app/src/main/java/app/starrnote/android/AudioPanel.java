package app.starrnote.android;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

final class AudioPanel {
    static final int PERMISSION = 28;
    private final MainActivity host;
    private Notebook waiting;
    private NotebookAudio recording;
    private MediaPlayer playback;
    private Runnable cancelTranscription;
    private ProgressDialog transcriptionProgress;
    private boolean stopping;

    AudioPanel(MainActivity host) { this.host = host; }

    void show(Notebook note) {
        Map<String, Runnable> options = new LinkedHashMap<>();
        if (stopping) options.put("Saving recording…", () -> host.message("Finishing the current recording"));
        else if (recording == null) options.put("Start recording", () -> record(note));
        else options.put("Stop and save recording", this::stopRecording);
        if (playback != null) options.put("Stop playback", this::stopPlayback);
        for (int i = note.clips.size() - 1; i >= 0; i--) {
            Notebook.Clip clip = note.clips.get(i);
            options.put(clip.name + " · " + (i + 1), () -> clip(note, clip));
        }
        host.options("Notebook audio", options);
    }

    private void record(Notebook note) {
        if (recording != null) return;
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            waiting = note;
            host.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION);
            return;
        }
        String name = host.library.newMedia("wav");
        File file = host.library.file(name);
        String page = note.current().id;
        stopPlayback();
        try {
            recording = new NotebookAudio(file, error -> host.runOnUiThread(() -> {
                recording = null;
                stopping = false;
                host.recordingChanged(false);
                if (error != null) {
                    file.delete();
                    if (!host.isDestroyed()) host.message(error.getLocalizedMessage());
                } else {
                    host.afterTask(() -> {
                        if (!host.library.notebooks.contains(note)) {
                            file.delete();
                            return;
                        }
                        note.clips.add(new Notebook.Clip(name, "Recording " + DateFormat.getDateTimeInstance(
                                DateFormat.SHORT, DateFormat.SHORT).format(new Date()), page));
                        host.changed(note);
                        host.save();
                        if (!host.isDestroyed()) host.message("Recording saved");
                    });
                }
            }));
            host.recordingChanged(true);
            host.message("Recording. Keep StarrNote in the foreground.");
        } catch (RuntimeException failure) {
            file.delete();
            host.message("Microphone unavailable: " + failure.getLocalizedMessage());
        }
    }

    void permissionResult(int[] grants) {
        Notebook pending = waiting;
        waiting = null;
        if (pending == null) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) record(pending);
        else host.message("Allow microphone access to record audio");
    }

    void stopRecording() {
        if (recording != null) {
            stopping = true;
            recording.stop();
        }
    }

    boolean isRecording() { return recording != null && !stopping; }

    private void clip(Notebook note, Notebook.Clip clip) {
        Map<String, Runnable> actions = new LinkedHashMap<>();
        actions.put("Play recording", () -> play(clip));
        if (!clip.page.isEmpty()) actions.put("Go to recording page", () -> {
            if (host.editor != null) host.editor.openLink("page:" + clip.page);
        });
        if (!clip.file.endsWith(".aud")) actions.put("Transcribe on this device", () -> transcribe(note, clip));
        actions.put("Edit transcript", () -> editTranscript(note, clip));
        actions.put("Generate AI summary", () -> {
            if (clip.transcript.trim().isEmpty()) {
                host.message("Transcribe the recording or add transcript text first");
                return;
            }
            new AlertDialog.Builder(host).setTitle("Transcript language")
                    .setItems(new String[]{"English", "Japanese", "Korean"}, (dialog, language) ->
                            host.task("Generating on-device AI summary…",
                                    () -> NotebookAudio.generativeSummary(host, clip.transcript, language), result -> {
                                        clip.summary = result;
                                        host.changed(note);
                                        summary(clip);
                                    })).setNegativeButton("Cancel", null).show();
        });
        actions.put("Extract key points", () -> {
            if (clip.transcript.trim().isEmpty()) {
                host.message("Transcribe the recording or add transcript text first");
                return;
            }
            clip.summary = NotebookAudio.summarize(clip.transcript);
            host.changed(note);
            summary(clip);
        });
        if (!clip.summary.isEmpty()) actions.put("View summary", () -> summary(clip));
        actions.put("Rename recording", () -> host.prompt("Recording title", "Title", clip.name, title -> {
            clip.name = title;
            host.changed(note);
        }));
        actions.put("Delete recording", () -> new AlertDialog.Builder(host).setTitle("Delete this recording?")
                .setPositiveButton("Delete", (d, i) -> {
                    stopPlayback();
                    note.clips.remove(clip);
                    host.changed(note);
                    host.save();
                }).setNegativeButton("Cancel", null).show());
        host.options(clip.name, actions);
    }

    private void play(Notebook.Clip clip) {
        stopPlayback();
        try {
            MediaPlayer player = new MediaPlayer();
            playback = player;
            player.setDataSource(host.library.file(clip.file).getAbsolutePath());
            player.setOnCompletionListener(completed -> stopPlayback());
            player.setOnPreparedListener(start -> {
                if (playback == start) start.start();
            });
            player.setOnErrorListener((broken, what, extra) -> {
                stopPlayback();
                host.message("Recording could not be played");
                return true;
            });
            player.prepareAsync();
            host.message("Playing " + clip.name);
        } catch (Exception failure) {
            stopPlayback();
            host.message("Recording could not be played");
        }
    }

    private void stopPlayback() {
        if (playback == null) return;
        playback.release();
        playback = null;
    }

    private void transcribe(Notebook note, Notebook.Clip clip) {
        if (host.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.message("Enable microphone permission to use Android speech recognition");
            return;
        }
        if (recording != null) {
            host.message("Stop recording before transcribing");
            return;
        }
        try {
            transcriptionProgress = new ProgressDialog(host);
            transcriptionProgress.setMessage("Transcribing recording…");
            transcriptionProgress.setOnCancelListener(dialog -> finishTranscription());
            transcriptionProgress.show();
            cancelTranscription = NotebookAudio.transcribe(host, host.library.file(clip.file), text -> {
                finishTranscription();
                clip.transcript = text;
                clip.summary = "";
                host.changed(note);
                editTranscript(note, clip);
            }, error -> {
                finishTranscription();
                host.message(error);
            });
        } catch (Exception failure) {
            finishTranscription();
            host.message(failure.getLocalizedMessage() == null ? "Transcription unavailable" : failure.getLocalizedMessage());
        }
    }

    private void finishTranscription() {
        if (cancelTranscription != null) cancelTranscription.run();
        cancelTranscription = null;
        if (transcriptionProgress != null) transcriptionProgress.dismiss();
        transcriptionProgress = null;
    }

    private void editTranscript(Notebook note, Notebook.Clip clip) {
        LinearLayout form = host.form();
        EditText text = host.field("Transcript", clip.transcript);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setMaxLines(10);
        form.addView(text);
        new AlertDialog.Builder(host).setTitle("Transcript").setView(form).setPositiveButton("Save", (d, i) -> {
            clip.transcript = text.getText().toString().trim();
            clip.summary = "";
            host.changed(note);
        }).setNegativeButton("Cancel", null).show();
    }

    private void summary(Notebook.Clip clip) {
        new AlertDialog.Builder(host).setTitle("Recording summary").setMessage(clip.summary)
                .setPositiveButton("Done", null).setNeutralButton("Copy", (d, i) -> host.getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("StarrNote summary", clip.summary))).show();
    }

    void close() {
        stopRecording();
        stopPlayback();
        finishTranscription();
    }
}
