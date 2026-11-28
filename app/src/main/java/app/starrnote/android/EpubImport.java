package app.starrnote.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

final class EpubImport {
    private static final long STORAGE_RESERVE = 32L * 1024 * 1024;
    private static final long CHECK_INTERVAL = 4L * 1024 * 1024;

    private EpubImport() {}

    static void copy(InputStream input, File target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long allowance = 0;
        try (FileOutputStream output = new FileOutputStream(target)) {
            for (int count; (count = input.read(buffer)) != -1;) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("EPUB import was interrupted.");
                if (count > allowance) {
                    long usable = target.getParentFile().getUsableSpace() - STORAGE_RESERVE;
                    if (usable < count) throw new IOException("There is not enough free device storage. Free some space and try again.");
                    allowance = Math.min(CHECK_INTERVAL, usable);
                }
                output.write(buffer, 0, count);
                allowance -= count;
            }
        }
    }
}
