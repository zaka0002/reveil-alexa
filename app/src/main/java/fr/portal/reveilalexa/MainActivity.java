package fr.portal.reveilalexa;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Réveil Alexa");
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(20);
        statusView.setPadding(0, 32, 0, 32);
        root.addView(statusView);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.addView(button("Démarrer", () -> WakeService.start(this)));
        row.addView(button("Arrêter", () -> stopService(new Intent(this, WakeService.class))));
        row.addView(button("Tester Alexa", () -> WakeService.launchAlexa(this)));
        root.addView(row);

        setContentView(root);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else {
            WakeService.start(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) WakeService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            statusView.setText(String.format(Locale.FRANCE,
                    "État : %s\nDernier mot reconnu : %s\nDéclenchements : %d",
                    WakeService.status, WakeService.lastHeard.isEmpty() ? "—" : WakeService.lastHeard,
                    WakeService.triggers));
            ui.postDelayed(this, 300);
        }
    };

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setOnClickListener(v -> action.run());
        return b;
    }
}
