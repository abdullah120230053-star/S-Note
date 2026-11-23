package app.starrnote.android;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class Imports {
    record Image(String name, float ratio) {}

    static Notebook read(Library library, ContentResolver resolver, Uri uri, String title, String mime) throws Exception {
        String lower = title.toLowerCase(Locale.ROOT);
        String name = title.replaceFirst("\\.(?=[^.]+$)[^.]+$", "");
        Notebook result = Notebook.create(name.isEmpty() ? "Imported document" : name, "Blank", 1);
        if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) {
            String asset = library.ingest(resolver, uri, "pdf");
            try {
                result.pages.clear();
                result.pages.addAll(library.pdfPages(asset));
            } catch (Exception failure) {
                library.file(asset).delete();
                throw failure;
            }
        } else if (mime != null && mime.startsWith("image/") || lower.matches(".*\\.(png|jpe?g|gif|webp|bmp|heic|heif|avif)$")) {
            String asset = library.ingest(resolver, uri, "img");
            Bitmap bitmap = library.image(asset);
            if (bitmap == null) {
                library.file(asset).delete();
                throw new IOException("Unsupported image");
            }
            Notebook.Page page = result.current();
            page.background = asset;
            page.height = Math.max(1, Math.min(12000, Math.round(840f * bitmap.getHeight() / bitmap.getWidth())));
            bitmap.recycle();
        } else {
            List<String> sections;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IOException("Cannot open selected document");
                boolean document = lower.endsWith(".docx") || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mime);
                boolean presentation = lower.endsWith(".pptx") || "application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(mime);
                if (document || presentation) sections = office(input, presentation);
                else if ((mime != null && mime.startsWith("text/")) || lower.endsWith(".md") || lower.endsWith(".txt")) {
                    sections = new ArrayList<>();
                    sections.add(new String(bytes(input, 2L * 1024 * 1024), StandardCharsets.UTF_8));
                } else throw new IOException("Choose a PDF, image, text file, DOCX, or PPTX");
            }
            result.pages.clear();
            for (String section : sections) result.pages.addAll(textPages(section));
            if (result.pages.isEmpty()) throw new IOException("This document contains no readable text");
        }
        return result;
    }

    private static byte[] bytes(InputStream input, long cap) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Library.copy(input, output, cap);
        return output.toByteArray();
    }

    private static List<String> office(InputStream source, boolean presentation) throws Exception {
        Map<Integer, String> ordered = new TreeMap<>();
        long size = 0;
        try (ZipInputStream input = new ZipInputStream(source)) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 4500) throw new IOException("Office document contains too many parts");
                String name = entry.getName();
                boolean keep = presentation ? name.matches("ppt/slides/slide[0-9]{1,5}\\.xml") : name.equals("word/document.xml");
                byte[] xml = bytes(input, Math.min(5L * 1024 * 1024, 64L * 1024 * 1024 - size));
                size += xml.length;
                if (keep) {
                    int index = presentation ? Integer.parseInt(name.substring(16, name.length() - 4)) : 1;
                    ordered.put(index, extract(xml));
                    if (!presentation) break;
                }
                input.closeEntry();
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private static String extract(byte[] xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new ByteArrayInputStream(xml), null);
        StringBuilder text = new StringBuilder();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG && parser.getName().equals("t")) text.append(parser.nextText());
            if (type == XmlPullParser.END_TAG && parser.getName().equals("p")) text.append('\n');
        }
        return text.toString();
    }

    private static List<Notebook.Page> textPages(String text) {
        List<Notebook.Page> pages = new ArrayList<>();
        List<String> wrapped = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            int cursor = 0;
            while (line.length() - cursor > 71) {
                int space = line.lastIndexOf(' ', cursor + 71);
                int next = space > cursor ? space : cursor + 71;
                if (next < line.length() && Character.isLowSurrogate(line.charAt(next))) next--;
                wrapped.add(line.substring(cursor, next));
                cursor = next < line.length() && line.charAt(next) == ' ' ? next + 1 : next;
            }
            wrapped.add(line.substring(cursor));
        }
        for (int start = 0; start < wrapped.size(); start += 30) {
            Notebook.Page page = new Notebook.Page();
            page.paper = "Blank";
            Notebook.Mark typed = new Notebook.Mark("Text", page.layers.get(0).id, 0xff193433, 20);
            typed.points.add(new Notebook.Point(50, 50, 1));
            typed.value = String.join("\n", wrapped.subList(start, Math.min(wrapped.size(), start + 30)));
            page.marks.add(typed);
            pages.add(page);
        }
        return pages;
    }
}
