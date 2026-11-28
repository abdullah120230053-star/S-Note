package app.starrnote.android;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class EpubBook implements AutoCloseable {
    private static final int MAX_ENTRY = 6 * 1024 * 1024;
    private static final int MAX_ILLUSTRATION = 32 * 1024 * 1024;
    private final ZipFile zip;
    final String title;
    final boolean rightToLeft;
    final List<String> chapters;

    EpubBook(java.io.File file) throws Exception {
        zip = new ZipFile(file);
        try {
            if (zip.size() > 25000) throw new IOException("This EPUB has too many entries.");
            String packagePath = null;
            try (InputStream stream = entry("META-INF/container.xml")) {
                XmlPullParser parser = parser(stream);
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (tag(parser, "rootfile")) {
                        packagePath = parser.getAttributeValue(null, "full-path");
                        if (packagePath != null) break;
                    }
                }
            }
            if (packagePath == null) throw new IOException("The EPUB package could not be found.");
            Map<String, String> manifest = new HashMap<>();
            List<String> readingOrder = new ArrayList<>();
            String name = "";
            boolean rtl = false;
            try (InputStream stream = entry(packagePath)) {
                XmlPullParser parser = parser(stream);
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (name.isEmpty() && tag(parser, "title")) name = parser.nextText().trim();
                    if (tag(parser, "item") && "application/xhtml+xml".equals(parser.getAttributeValue(null, "media-type"))) {
                        String target = resolve(packagePath, parser.getAttributeValue(null, "href"));
                        if (target != null) manifest.put(parser.getAttributeValue(null, "id"), target);
                    }
                    if (tag(parser, "spine")) rtl = "rtl".equals(parser.getAttributeValue(null, "page-progression-direction"));
                    if (tag(parser, "itemref") && !"no".equals(parser.getAttributeValue(null, "linear"))) {
                        readingOrder.add(parser.getAttributeValue(null, "idref"));
                    }
                }
            }
            chapters = new ArrayList<>();
            for (String id : readingOrder) {
                String chapter = manifest.get(id);
                if (chapter != null) chapters.add(chapter);
            }
            if (chapters.isEmpty()) throw new IOException("No readable EPUB chapters were found.");
            title = name.isEmpty() ? "Untitled book" : name;
            rightToLeft = rtl;
        } catch (Exception failure) {
            zip.close();
            throw failure;
        }
    }

    String chapter(int index) throws IOException {
        try (InputStream stream = entry(chapters.get(index));
             java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream()) {
            Library.copy(stream, data, MAX_ENTRY);
            return data.toString("UTF-8");
        }
    }

    android.graphics.drawable.Drawable illustration(int chapter, String href, int maxWidth, int maxHeight) {
        String name = resolve(chapters.get(chapter), href);
        if (name == null) return null;
        try {
            android.graphics.BitmapFactory.Options sizing = new android.graphics.BitmapFactory.Options();
            sizing.inJustDecodeBounds = true;
            try (InputStream stream = entry(name, MAX_ILLUSTRATION)) {
                android.graphics.BitmapFactory.decodeStream(stream, null, sizing);
            }
            if (sizing.outWidth <= 0 || sizing.outHeight <= 0) return null;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = 1;
            while (sizing.outWidth / options.inSampleSize > maxWidth
                    || sizing.outHeight / options.inSampleSize > maxHeight) options.inSampleSize *= 2;
            try (InputStream stream = entry(name, MAX_ILLUSTRATION)) {
                android.graphics.Bitmap image = android.graphics.BitmapFactory.decodeStream(stream, null, options);
                if (image == null) return null;
                android.graphics.drawable.BitmapDrawable drawable = new android.graphics.drawable.BitmapDrawable(
                        android.content.res.Resources.getSystem(), image);
                float scale = Math.min(1f, Math.min(maxWidth / (float) image.getWidth(), maxHeight / (float) image.getHeight()));
                drawable.setBounds(0, 0, Math.max(1, Math.round(image.getWidth() * scale)),
                        Math.max(1, Math.round(image.getHeight() * scale)));
                return drawable;
            }
        } catch (IOException | RuntimeException invalid) { return null; }
    }

    private InputStream entry(String name) throws IOException {
        return entry(name, MAX_ENTRY);
    }

    private InputStream entry(String name, int sizeLimit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > sizeLimit) {
            throw new IOException("An EPUB entry is missing or too large.");
        }
        return zip.getInputStream(entry);
    }

    private static XmlPullParser parser(InputStream input) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        }
        parser.setInput(input, null);
        return parser;
    }

    private static boolean tag(XmlPullParser parser, String name) throws Exception {
        return parser.getEventType() == XmlPullParser.START_TAG && name.equals(parser.getName());
    }

    private static String resolve(String source, String href) {
        if (href == null || href.isEmpty()) return null;
        try {
            URI uri = URI.create(href);
            if (uri.isAbsolute() || uri.getAuthority() != null || uri.getPath() == null || uri.getPath().startsWith("/")) return null;
            Path folder = java.nio.file.Paths.get(source).getParent();
            Path path = (folder == null ? java.nio.file.Paths.get(uri.getPath()) : folder.resolve(uri.getPath())).normalize();
            return path.startsWith("..") ? null : path.toString().replace('\\', '/');
        } catch (IllegalArgumentException invalid) { return null; }
    }

    @Override public void close() throws IOException { zip.close(); }
}
