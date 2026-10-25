package fr.portal.reveilalexa;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keyword spotting with Vosk and a small French model: the recognizer is restricted to the
 * grammar ["alexa", "[unk]"], so anything else lands on [unk]. Feed 16 kHz mono PCM chunks.
 */
public class VoskDetector implements AutoCloseable {
    private static final String TAG = "ReveilAlexa";
    private static final String ASSET_DIR = "model-fr";
    private static final String KEYWORD = "alexa";
    // Only a final result counts, and its word confidence must reach this: partial hypotheses
    // fired on stream-start noise every time the mic was reopened.
    private static final double MIN_CONFIDENCE = 0.85;

    private final Model model;
    private final Recognizer recognizer;

    /** Last text the recognizer settled on, for the status screen. */
    public volatile String lastHeard = "";

    public VoskDetector(Context ctx, float sampleRate) throws IOException {
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        File dir = unpackModel(ctx);
        model = new Model(dir.getAbsolutePath());
        recognizer = new Recognizer(model, sampleRate, "[\"" + KEYWORD + "\", \"[unk]\"]");
        recognizer.setWords(true);
    }

    public void reset() {
        recognizer.reset();
    }

    /** @return true when a final result contains "alexa" with enough confidence. */
    public boolean process(short[] chunk) {
        if (!recognizer.acceptWaveForm(chunk, chunk.length)) return false;
        double best = -1;
        String text = "";
        try {
            JSONObject res = new JSONObject(recognizer.getResult());
            text = res.optString("text", "");
            org.json.JSONArray words = res.optJSONArray("result");
            if (words != null) {
                for (int i = 0; i < words.length(); i++) {
                    JSONObject w = words.getJSONObject(i);
                    if (KEYWORD.equals(w.optString("word"))) best = Math.max(best, w.optDouble("conf", 0));
                }
            }
        } catch (Exception e) {
            return false;
        }
        if (best >= 0) {
            lastHeard = String.format(java.util.Locale.ROOT, "%s (confiance %.2f)", text, best);
        } else if (!text.isEmpty()) {
            lastHeard = text;
        }
        // "alexa" must end the utterance (silence right after it): inside running speech (TV,
        // conversation) Vosk's two-word grammar maps look-alike syllables onto it, which gave
        // "alexa [unk]" false triggers; "[unk] alexa" is how real calls often come out.
        return best >= MIN_CONFIDENCE && text.trim().endsWith(KEYWORD);
    }

    @Override
    public void close() {
        recognizer.close();
        model.close();
    }

    private static String field(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Copies the model out of the APK assets once per app version (Vosk needs real files). */
    private static File unpackModel(Context ctx) throws IOException {
        File dir = new File(ctx.getFilesDir(), ASSET_DIR);
        File marker = new File(dir, ".version");
        String version;
        try {
            version = String.valueOf(ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).getLongVersionCode());
        } catch (Exception e) {
            version = "0";
        }
        if (marker.exists() && version.equals(read(marker))) return dir;

        Log.i(TAG, "copie du modèle Vosk…");
        deleteRecursive(dir);
        copyAssetDir(ctx.getAssets(), ASSET_DIR, dir);
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(version.getBytes());
        }
        return dir;
    }

    private static void copyAssetDir(AssetManager am, String path, File dest) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            dest.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            return;
        }
        dest.mkdirs();
        for (String c : children) copyAssetDir(am, path + "/" + c, new File(dest, c));
    }

    private static String read(File f) {
        // InputStream.readAllBytes() only exists from Android 13; the Portal runs Android 10.
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
