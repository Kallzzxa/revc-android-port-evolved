package com.revc.game;

import com.github.junrar.Archive;
import com.github.junrar.Junrar;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.ContentDescription;
import com.github.junrar.rarfile.FileHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads the GTA Vice City PC data pack and extracts it into the game
 * folder, so the player never has to copy files by hand.
 *
 * <p>Source is the user-provided pack on Pixeldrain (direct link, supports
 * HTTP Range, so a failed download resumes instead of restarting). The pack
 * is a solid RAR 5 archive whose files all live under one top-level folder
 * ("Grand Theft Auto Vice City/"); that folder is stripped on extract so
 * the game sees the plain PC layout (models/gta3.img, data/, audio/, ...).
 * Extraction is done with junrar (pure Java, RAR up to v7).
 */
public final class GameDataDownloader {

    /** Direct download of the user-provided PC data pack (Pixeldrain). */
    public static final String DATA_URL = "https://pixeldrain.com/api/file/PfZcbQME";

    /** Exact size of the pack, in bytes (verified 2026-11-17). */
    public static final long EXPECTED_SIZE = 1190500289L;

    /** Unpacked size of the pack, in bytes (verified with unrar 2026-11-17). */
    public static final long EXTRACTED_SIZE = 1760546862L;

    /** RAR + extracted peak usage, plus margin. Checked before downloading. */
    public static final long REQUIRED_FREE_BYTES = 3_000_000_000L;

    private static final String DOWNLOAD_DIR_NAME = ".download";
    private static final String RAR_FILE_NAME = "vc-data.rar";
    private static final String TOP_FOLDER_PREFIX = "Grand Theft Auto Vice City/";

    private static volatile HttpURLConnection currentConnection;

    private GameDataDownloader() {
    }

    public interface DownloadListener {
        void onProgress(long downloaded, long total, long bytesPerSec);
    }

    public interface ExtractListener {
        void onProgress(int done, int total, String currentName);
    }

    /** True when the game can run from this folder (the two files reVC needs). */
    public static boolean isGameDataPresent(File gameDir) {
        return new File(gameDir, "models/gta3.img").isFile()
                && new File(gameDir, "data/gta_vc.dat").isFile();
    }

    public static File getDownloadFile(File gameDir) {
        return new File(new File(gameDir, DOWNLOAD_DIR_NAME), RAR_FILE_NAME);
    }

    public static void deleteDownload(File gameDir) {
        File rar = getDownloadFile(gameDir);
        if (rar.exists()) {
            rar.delete();
        }
    }

    /** Unblocks a download stuck in read(); the cancelled flag stops the loop. */
    public static void cancelCurrentDownload() {
        HttpURLConnection conn = currentConnection;
        if (conn != null) {
            conn.disconnect();
        }
    }

    public static String formatMB(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    /**
     * Downloads the pack, resuming a partial file when one exists. Returns
     * early (without throwing) when cancelled; throws on HTTP or size errors.
     */
    public static void download(File gameDir, AtomicBoolean cancelled, DownloadListener listener)
            throws IOException {
        File rar = getDownloadFile(gameDir);
        File dir = rar.getParentFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        long downloaded = rar.exists() ? rar.length() : 0;
        if (downloaded > EXPECTED_SIZE) {
            rar.delete();
            downloaded = 0;
        }
        if (downloaded == EXPECTED_SIZE) {
            listener.onProgress(downloaded, EXPECTED_SIZE, 0);
            return;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(DATA_URL).openConnection();
        currentConnection = conn;
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "reVC-data-downloader/1.0");
            if (downloaded > 0) {
                conn.setRequestProperty("Range", "bytes=" + downloaded + "-");
            }
            conn.connect();

            int code = conn.getResponseCode();
            if (downloaded > 0 && code == HttpURLConnection.HTTP_OK) {
                // Server ignored Range: restart from scratch.
                downloaded = 0;
            } else if (code != HttpURLConnection.HTTP_OK
                    && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code);
            }
            long remaining = conn.getHeaderFieldLong("Content-Length", -1);
            long total = remaining > 0 ? downloaded + remaining : EXPECTED_SIZE;

            long lastReport = System.currentTimeMillis();
            long lastBytes = downloaded;
            try (RandomAccessFile out = new RandomAccessFile(rar, "rw");
                 InputStream in = conn.getInputStream()) {
                out.seek(downloaded);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled.get()) {
                        return;
                    }
                    out.write(buf, 0, n);
                    downloaded += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 500) {
                        long speed = (downloaded - lastBytes) * 1000
                                / Math.max(1, now - lastReport);
                        lastReport = now;
                        lastBytes = downloaded;
                        listener.onProgress(downloaded, total, speed);
                    }
                }
            }
            listener.onProgress(downloaded, total, 0);
            if (cancelled.get()) {
                return;
            }
            if (rar.length() != EXPECTED_SIZE) {
                throw new IOException("Ukuran file tidak cocok (" + formatMB(rar.length())
                        + ", harusnya " + formatMB(EXPECTED_SIZE) + "). Coba lagi.");
            }
        } finally {
            if (currentConnection == conn) {
                currentConnection = null;
            }
            conn.disconnect();
        }
    }

    /**
     * Extracts the pack into the game folder, stripping its top-level
     * folder. Must run in header order (the archive is solid). Returns
     * early (without throwing) when cancelled; a cancelled or failed run
     * can simply be started again, already-extracted files are overwritten.
     */
    public static void extract(File gameDir, AtomicBoolean cancelled, ExtractListener listener)
            throws IOException, RarException {
        File rar = getDownloadFile(gameDir);
        List<ContentDescription> contents = Junrar.getContentsDescription(rar);
        int total = contents.size();
        int done = 0;
        String gameDirPath = gameDir.getCanonicalPath();
        try (Archive archive = new Archive(rar)) {
            FileHeader header;
            while ((header = archive.nextFileHeader()) != null) {
                if (cancelled.get()) {
                    return;
                }
                String name = header.getFileName().replace('\\', '/');
                done++;
                if (header.isDirectory()) {
                    listener.onProgress(done, total, name);
                    continue;
                }
                String relative = stripTopFolder(name);
                File dest = new File(gameDir, relative);
                String destPath = dest.getCanonicalPath();
                if (!destPath.startsWith(gameDirPath + File.separator)) {
                    throw new IOException("Nama file tidak aman di dalam RAR: " + name);
                }
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (OutputStream out = new FileOutputStream(dest)) {
                    archive.extractFile(header, out);
                }
                listener.onProgress(done, total, relative);
            }
        }
    }

    private static String stripTopFolder(String name) {
        if (name.startsWith(TOP_FOLDER_PREFIX)) {
            return name.substring(TOP_FOLDER_PREFIX.length());
        }
        int slash = name.indexOf('/');
        if (slash >= 0) {
            return name.substring(slash + 1);
        }
        return name;
    }
}
