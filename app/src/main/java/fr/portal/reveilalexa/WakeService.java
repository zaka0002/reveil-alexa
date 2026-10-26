package fr.portal.reveilalexa;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** Foreground service: listens for "Alexa" and opens the Alexa app's voice UI. */
public class WakeService extends Service {
    private static final String TAG = "ReveilAlexa";
    private static final String CHANNEL = "ecoute";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHUNK = 800; // 50 ms: finer steps let Vosk report the endpoint sooner

    // Read by MainActivity for the live status display.
    public static volatile boolean running;
    public static volatile String lastHeard = "";
    public static volatile int triggers;
    public static volatile String status = "arrêté";

    private static final long REVIVE_MIN_INTERVAL_MS = 10000;
    // Short on purpose: Android 10 can force a mic reopen every ~10 s, and every "Alexa" said
    // inside this window is lost. The final-result + confidence + utterance-final checks in
    // VoskDetector are what keep noise out now.
    private static final long WARMUP_MS = 800;
    private static volatile boolean reopenRequested;

    private Thread worker;
    private volatile boolean stop;
    private long lastRevive;

    /** Called by ReviveActivity while it is on top, so the new capture starts from the foreground. */
    static void requestReopen() {
        reopenRequested = true;
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, WakeService.class);
        ctx.startForegroundService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Écoute « Alexa »",
                NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Réveil Alexa")
                .setContentText("À l'écoute de « Alexa »")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(1, n);
        registerReceiver(turnDone, new android.content.IntentFilter(FALCON_TURN_DONE));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (worker == null || !worker.isAlive()) {
            stop = false;
            worker = new Thread(this::loop, "wakeword");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        if (worker != null) worker.interrupt();
        try {
            unregisterReceiver(turnDone);
        } catch (IllegalArgumentException ignored) {
        }
        running = false;
        status = "arrêté";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final java.text.SimpleDateFormat JOURNAL_TIME =
            new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.ROOT);

    /**
     * Logcat on the Portal only keeps ~1 minute, so events also go to
     * /sdcard/Android/data/fr.portal.reveilalexa/files/journal.txt (readable over adb).
     */
    private void journal(String msg) {
        Log.i(TAG, msg);
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            java.io.File f = new java.io.File(dir, "journal.txt");
            if (f.length() > 512 * 1024) f.delete();
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(JOURNAL_TIME.format(new java.util.Date()) + " " + msg + "\n");
            }
        } catch (java.io.IOException ignored) {
        }
    }

    private void loop() {
        running = true;
        status = "chargement du modèle…";
        try (VoskDetector det = new VoskDetector(this, SAMPLE_RATE)) {
            short[] chunk = new short[CHUNK];
            boolean needRevive = false;
            while (!stop) {
                // On Android 10 a capture opened from the background gets silenced, so
                // (re)open it while our invisible activity is on top.
                if (needRevive) {
                    reviveAndWait();
                    needRevive = false;
                }
                AudioRecord rec = openMic();
                if (rec == null) {
                    status = "micro indisponible, nouvel essai…";
                    SystemClock.sleep(3000);
                    continue;
                }
                status = "à l'écoute";
                det.reset();
                long openedAt = SystemClock.elapsedRealtime();
                boolean fired = false;
                int silentChecks = 0;
                int frames = 0;
                // Start from the detector's last text so a reopen doesn't re-log the previous word.
                String heard = det.lastHeard;
                try {
                    while (!stop) {
                        if (!readFully(rec, chunk)) break;
                        // Silence watchdog: ignore the first seconds (policy settles), then require
                        // ~3 s of silenced capture with nobody else recording before reviving.
                        long now = SystemClock.elapsedRealtime();
                        if (++frames % 10 == 0 && now - openedAt > 3000) {
                            silentChecks = isSilenced(rec) ? silentChecks + 1 : 0;
                            if (silentChecks >= 3 && othersRecording(rec.getAudioSessionId()) == 0
                                    && now - lastRevive > REVIVE_MIN_INTERVAL_MS) {
                                status = "micro coupé par Android, reprise…";
                                journal("micro coupé, reprise via le premier plan");
                                needRevive = true;
                                break;
                            }
                        }
                        boolean hit = det.process(chunk);
                        boolean warmup = now - openedAt < WARMUP_MS;
                        if (!det.lastHeard.equals(heard)) {
                            heard = det.lastHeard;
                            lastHeard = heard;
                            journal("entendu : " + heard + " (+" + (now - openedAt) + " ms"
                                    + (warmup ? ", ignoré : chauffe)" : ")"));
                        }
                        if (warmup) {
                            // Right after the mic opens Vosk tends to snap noise onto "alexa";
                            // let it settle, then start from a clean state.
                            if (hit || now - openedAt + 100 >= WARMUP_MS) det.reset();
                            continue;
                        }
                        if (hit) {
                            fired = true;
                            journal("Alexa détecté");
                            break;
                        }
                    }
                } finally {
                    // Release the mic so Alexa can record.
                    rec.stop();
                    rec.release();
                }
                if (fired) {
                    triggers++;
                    status = "Alexa détecté !";
                    launchAlexa();
                    new Thread(WakeService::beep, "beep").start(); // don't delay Alexa

                    waitForAlexaDone();
                    needRevive = true;
                } else if (!stop && !needRevive) {
                    Log.w(TAG, "lecture micro interrompue, réouverture");
                    SystemClock.sleep(500);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "erreur écoute", e);
            status = "erreur : " + e.getMessage();
        }
        running = false;
    }

    private AudioRecord openMic() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, CHUNK * 2 * 4);
        try {
            AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                return null;
            }
            rec.startRecording();
            return rec;
        } catch (Exception e) {
            Log.w(TAG, "ouverture micro", e);
            return null;
        }
    }

    /** True when the audio policy is feeding us silence (another app took the mic, or we were restarted in background). */
    private boolean isSilenced(AudioRecord rec) {
        if (android.os.Build.VERSION.SDK_INT < 29) return false;
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        for (android.media.AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) {
            if (c.getClientAudioSessionId() == rec.getAudioSessionId()) return c.isClientSilenced();
        }
        return false;
    }

    /**
     * Captures by other apps that actually hear something. Silenced ones don't count: HA Bridge,
     * for one, keeps a capture open all the time, silenced whenever its dashboard isn't in front.
     */
    private int othersRecording(int ownSession) {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        int n = 0;
        for (android.media.AudioRecordingConfiguration c : am.getActiveRecordingConfigurations()) {
            if (c.getClientAudioSessionId() == ownSession) continue;
            if (android.os.Build.VERSION.SDK_INT >= 29 && c.isClientSilenced()) continue;
            n++;
        }
        return n;
    }

    /**
     * Waits until Alexa has stopped listening and speaking (or 60 s), so reviving won't interrupt it.
     * With falcon, its TURN_DONE broadcast ends the turn; a TURN_DONE right after LISTEN is the
     * known cold-start abort (first turn after falcon's activity was created), so LISTEN is re-fired
     * once. Either way we then wait for the mic and Alexa's speech to go quiet.
     */
    private void waitForAlexaDone() {
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        long start = SystemClock.elapsedRealtime();
        if (usingFalcon) {
            boolean retried = false;
            while (!stop && SystemClock.elapsedRealtime() - start < 60000) {
                if (turnDoneAt != 0) {
                    if (!retried && turnDoneAt - listenAt < FALCON_COLD_ABORT_MS) {
                        retried = true;
                        turnDoneAt = 0;
                        journal("Alexa : échec à froid, nouvelle écoute");
                        sendFalconListen(this);
                    } else {
                        break;
                    }
                }
                SystemClock.sleep(100);
            }
        } else {
            SystemClock.sleep(2000); // let Alexa open its mic
        }
        int quiet = 0;
        while (!stop && quiet < 3 && SystemClock.elapsedRealtime() - start < 60000) {
            boolean busy = othersRecording(-1) > 0; // our mic is released here
            for (android.media.AudioPlaybackConfiguration p : am.getActivePlaybackConfigurations()) {
                int usage = p.getAudioAttributes().getUsage();
                if (usage == android.media.AudioAttributes.USAGE_ASSISTANT
                        || usage == android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION
                        || usage == android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) {
                    busy = true;
                }
            }
            quiet = busy ? 0 : quiet + 1;
            SystemClock.sleep(500);
        }
        journal("Alexa terminée après " + (SystemClock.elapsedRealtime() - start) + " ms");
    }

    /** Shows the invisible ReviveActivity and returns once it is on top (or after 3 s). */
    private void reviveAndWait() {
        lastRevive = SystemClock.elapsedRealtime();
        reopenRequested = false;
        Intent i = new Intent(this, ReviveActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
        long deadline = SystemClock.elapsedRealtime() + 3000;
        while (!reopenRequested && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
        journal(reopenRequested ? "réouverture du micro au premier plan" : "reprise : activité non affichée");
        reopenRequested = false;
    }

    private static void beep() {
        try {
            android.media.ToneGenerator tg =
                    new android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150);
            SystemClock.sleep(200);
            tg.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "bip", e);
        }
    }

    private static boolean readFully(AudioRecord rec, short[] buf) {
        int off = 0;
        while (off < buf.length) {
            int r = rec.read(buf, off, buf.length - off);
            if (r <= 0) return false;
            off += r;
        }
        return true;
    }

    // Meta's own Alexa client, revived by Immortal / HA Bridge. Preferred over the phone app.
    static final String FALCON = "com.amazon.alexa.multimodal.falcon";
    private static final String FALCON_LISTEN = FALCON + ".LISTEN";
    private static final String FALCON_TURN_DONE = FALCON + ".TURN_DONE";
    // On Android 10 falcon must be the top activity before LISTEN, or its own capture is
    // silenced and it hears nothing. Same settle time HA Bridge uses.
    private static final long FALCON_FOREGROUND_MS = 800;
    private static final long FALCON_COLD_ABORT_MS = 1500;

    private static volatile long listenAt;
    private static volatile long turnDoneAt;
    private boolean usingFalcon;

    private final android.content.BroadcastReceiver turnDone = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            turnDoneAt = SystemClock.elapsedRealtime();
        }
    };

    static boolean hasFalcon(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(FALCON, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void sendFalconListen(Context ctx) {
        listenAt = SystemClock.elapsedRealtime();
        turnDoneAt = 0;
        ctx.sendBroadcast(new Intent(FALCON_LISTEN).setPackage(FALCON)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
    }

    /** Brings falcon to the front, then (off the calling thread) tells it to listen. */
    static void launchAlexa(Context ctx) {
        if (hasFalcon(ctx)) {
            try {
                ctx.startActivity(new Intent().setClassName(FALCON, FALCON + ".SIMActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> sendFalconListen(ctx), FALCON_FOREGROUND_MS);
                return;
            } catch (Exception e) {
                Log.w(TAG, "lancement falcon", e);
            }
        }
        String[] activities = {
                "com.amazon.alexa.voice.VoiceHandsFreeSearchActivity",
                "com.amazon.dee.app.ui.voice.LaunchAlexaActivity",
        };
        for (String cls : activities) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName("com.amazon.dee.app", cls));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Exception e) {
                Log.w(TAG, "lancement " + cls, e);
            }
        }
    }

    private void launchAlexa() {
        usingFalcon = hasFalcon(this);
        turnDoneAt = 0; // a stale TURN_DONE from an earlier turn must not end this one early
        journal(usingFalcon ? "ouverture de l'Alexa de Meta (falcon)" : "ouverture de l'appli Amazon Alexa");
        launchAlexa(this);
    }
}
