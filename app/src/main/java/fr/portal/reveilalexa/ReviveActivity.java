package fr.portal.reveilalexa;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

/**
 * Invisible activity: while it is on top, the service reopens the mic. Android 10 keeps a
 * capture started from the foreground alive in the background, but silences one started
 * (or interrupted) while in the background.
 */
public class ReviveActivity extends Activity {
    @Override
    protected void onResume() {
        super.onResume();
        WakeService.requestReopen();
        // Send the task to the back instead of finishing: the capture only stays unsilenced
        // while one of our activities is still alive (stopped) in the background.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            moveTaskToBack(true);
            overridePendingTransition(0, 0);
        }, 800);
    }
}
