package app.starrnote.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class Library {
    private static final long MAX_ASSET = 4L * 1024 * 1024 * 1024;
    private static final long MAX_INDEX = 64L * 1024 * 1024;
    private static final int MAX_ARCHIVE_FILES = 25000;

    private final File media;
    private final File cache;
    private final AtomicFile index;
    final List<Notebook> notebooks = new ArrayList<>();
    private final Map<String, Long> deletions = new HashMap<>();

    Library(Context context) throws IOException, JSONException {
        media = new File(context.getFilesDir(), "media");
        cache = context.getCacheDir();
        if (!media.isDirectory() && !media.mkdirs()) throw new IOException("Media storage is unavailable");
        index = new AtomicFile(new File(context.getFilesDir(), "library.json"));
        if (index.getBaseFile().exists() || new File(index.getBaseFile() + ".bak").exists()) {
            ArchiveData stored = decode(index.readFully());
            notebooks.addAll(stored.notebooks());
            deletions.putAll(stored.deletions());
        }
    }

    private byte[] encode(List<Notebook> source) throws JSONException {
        JSONArray array = new JSONArray();
        for (Notebook note : source) array.put(note.json());
        return new JSONObject().put("version", 1).put("notebooks", array).put("deletions", new JSONObject(deletions)).toString()
                .getBytes(StandardCharsets.UTF_8);
    }

    private record ArchiveData(List<Notebook> notebooks, Map<String, Long> deletions) {}

    private ArchiveData decode(byte[] input) throws JSONException {
        JSONObject data = new JSONObject(new String(input, StandardCharsets.UTF_8));
        if (data.getInt("version") != 1) throw new JSONException("Unsupported StarrNote archive version");
        List<Notebook> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        JSONArray notes = data.getJSONArray("notebooks");
        for (int i = 0; i < notes.length(); i++) {
            Notebook note = Notebook.read(notes.getJSONObject(i));
            if (!ids.add(note.id)) throw new JSONException("Duplicate notebook");
            result.add(note);
        }
        Map<String, Long> removed = new HashMap<>();
        JSONObject records = data.optJSONObject("deletions");
        if (records != null) {
            Iterator<String> keys = records.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                removed.put(id, records.getLong(id));
            }
        }
        return new ArchiveData(result, removed);
    }

    void save() throws IOException, JSONException {
        byte[] data = encode(notebooks);
        FileOutputStream stream = index.startWrite();
        try {
            stream.write(data);
            index.finishWrite(stream);
        } catch (IOException failure) {
            index.failWrite(stream);
            throw failure;
        }
    }

    File file(String name) {
        if (!name.matches("[a-f0-9-]{36}\\.[a-z0-9]{2,5}")) {
            throw new IllegalArgumentException("Invalid media name");
        }
        return new File(media, name);
    }

    String newMedia(String extension) { return Notebook.id() + "." + extension; }

    void remove(Notebook notebook) {
        notebooks.remove(notebook);
        deletions.merge(notebook.id, System.currentTimeMillis(), Math::max);
    }

    String ingest(ContentResolver resolver, Uri uri, String extension) throws IOException {
        String name = newMedia(extension);
        File target = file(name);
        boolean complete = false;
        try (InputStream input = resolver.openInputStream(uri);
             OutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new IOException("Cannot open this file");
            copy(input, output, MAX_ASSET);
            complete = true;
        } finally {
            if (!complete) target.delete();
        }
        return name;
    }

    static String displayName(ContentResolver resolver, Uri uri, String fallback) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) {
            // A document provider can supply a stream without display metadata.
        }
        return fallback;
    }

    List<Notebook.Page> pdfPages(String name) throws IOException {
        List<Notebook.Page> result = new ArrayList<>();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file(name), ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(descriptor)) {
            for (int i = 0; i < pdf.getPageCount(); i++) {
                try (PdfRenderer.Page source = pdf.openPage(i)) {
                    Notebook.Page page = new Notebook.Page();
                    page.paper = "Blank";
                    page.background = name;
                    page.pdfIndex = i;
                    page.width = 840;
                    page.height = Math.max(1, Math.min(12000, Math.round(840f * source.getHeight() / source.getWidth())));
                    result.add(page);
                }
            }
        }
        if (result.isEmpty()) throw new IOException("This PDF has no pages");
        return result;
    }

    Bitmap background(Notebook.Page page) throws IOException {
        return background(page, 0);
    }

    Bitmap background(Notebook.Page page, int maxEdge) throws IOException {
        if (page.background.isEmpty()) return null;
        File asset = file(page.background);
        if (page.pdfIndex < 0) return image(asset, maxEdge > 0 ? maxEdge : 2000);
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(asset, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(descriptor);
             PdfRenderer.Page pdfPage = pdf.openPage(page.pdfIndex)) {
            int edge = maxEdge > 0 ? maxEdge : 1900;
            float scale = Math.min(2f, edge / (float) Math.max(pdfPage.getWidth(), pdfPage.getHeight()));
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(pdfPage.getWidth() * scale)),
                    Math.max(1, Math.round(pdfPage.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }

    Bitmap image(String name) { return image(file(name), 2000); }

    Bitmap pdfDetail(Notebook.Page page, android.graphics.RectF region, float scale) throws IOException {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file(page.background), ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf = new PdfRenderer(descriptor);
             PdfRenderer.Page source = pdf.openPage(page.pdfIndex)) {
            int width = Math.max(1, Math.round(region.width() * scale));
            int height = Math.max(1, Math.round(region.height() * scale));
            float sx = width / region.width(), sy = height / region.height();
            Bitmap detail = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            detail.eraseColor(Color.WHITE);
            android.graphics.Matrix transform = new android.graphics.Matrix();
            transform.setScale(sx * page.width / source.getWidth(), sy * page.height / source.getHeight());
            transform.postTranslate(-region.left * sx, -region.top * sy);
            source.render(detail, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return detail;
        }
    }

    private Bitmap image(File file, int maxEdge) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outHeight, options.outWidth) / options.inSampleSize > maxEdge) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private Set<String> assets(List<Notebook> notes) {
        Set<String> names = new LinkedHashSet<>();
        for (Notebook note : notes) {
            for (Notebook.Page page : note.pages) {
                if (!page.background.isEmpty()) names.add(page.background);
                for (Notebook.Mark mark : page.marks) if ("Image".equals(mark.tool)) names.add(mark.value);
                for (Notebook.DeletedLayer deleted : page.deletedLayers) {
                    for (Notebook.Mark mark : deleted.marks()) if ("Image".equals(mark.tool)) names.add(mark.value);
                }
            }
            for (Notebook.Clip clip : note.clips) names.add(clip.file);
        }
        return names;
    }

    File archive() throws IOException, JSONException {
        File temp = File.createTempFile("starrnote-library", ".zip", cache);
        Set<String> names = assets(notebooks);
        byte[] data = encode(notebooks);
        boolean complete = false;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
            zip.putNextEntry(new ZipEntry("library.json"));
            zip.write(data);
            zip.closeEntry();
            for (String name : names) {
                zip.putNextEntry(new ZipEntry("media/" + name));
                try (InputStream input = new FileInputStream(file(name))) {
                    copy(input, zip, MAX_ASSET);
                }
                zip.closeEntry();
            }
            zip.finish();
            complete = true;
        } finally {
            if (!complete) temp.delete();
        }
        return temp;
    }

    int restore(InputStream input) throws IOException, JSONException {
        Map<String, File> staged = new HashMap<>();
        ArchiveData incoming = null;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            long total = 0;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ARCHIVE_FILES) throw new IOException("Backup contains too many files");
                String name = entry.getName();
                if (name.equals("library.json") && incoming == null) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    copy(zip, bytes, MAX_INDEX);
                    incoming = decode(bytes.toByteArray());
                } else if (name.startsWith("media/") && !entry.isDirectory()) {
                    String key = name.substring(6);
                    file(key);
                    if (staged.containsKey(key)) throw new IOException("Duplicate backup asset");
                    File temporary = File.createTempFile("starrnote-import", ".asset", cache);
                    staged.put(key, temporary);
                    try (OutputStream output = new FileOutputStream(temporary)) {
                        total += copy(zip, output, Math.min(MAX_ASSET, 4 * MAX_ASSET - total));
                    }
                } else {
                    throw new IOException("Unrecognized backup entry");
                }
                zip.closeEntry();
            }
            if (incoming == null) throw new IOException("This is not a StarrNote backup");
            for (String name : assets(incoming.notebooks())) {
                if (!file(name).exists() && !staged.containsKey(name)) throw new IOException("Backup media is missing");
            }
            for (Map.Entry<String, File> asset : staged.entrySet()) {
                File destination = file(asset.getKey());
                if (!destination.exists()) Files.move(asset.getValue().toPath(), destination.toPath());
            }
            return merge(incoming);
        } finally {
            for (File temp : staged.values()) temp.delete();
        }
    }

    private int merge(ArchiveData incoming) throws IOException, JSONException {
        List<Notebook> old = new ArrayList<>(notebooks);
        Map<String, Long> oldDeletions = new HashMap<>(deletions);
        incoming.deletions().forEach((id, timestamp) -> deletions.merge(id, timestamp, Math::max));
        notebooks.removeIf(note -> note.updated <= deletions.getOrDefault(note.id, Long.MIN_VALUE));
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < notebooks.size(); i++) positions.put(notebooks.get(i).id, i);
        int imported = 0;
        for (Notebook note : incoming.notebooks()) {
            if (note.updated <= deletions.getOrDefault(note.id, Long.MIN_VALUE)) continue;
            Integer position = positions.get(note.id);
            if (position == null) {
                notebooks.add(note);
                imported++;
            } else if (note.updated > notebooks.get(position).updated) {
                notebooks.set(position, note);
                imported++;
            }
        }
        try {
            save();
            return imported;
        } catch (IOException | JSONException failure) {
            notebooks.clear();
            notebooks.addAll(old);
            deletions.clear();
            deletions.putAll(oldDeletions);
            throw failure;
        }
    }

    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            size += count;
            if (size > limit) throw new IOException("File is too large");
            output.write(buffer, 0, count);
        }
        return size;
    }
}
