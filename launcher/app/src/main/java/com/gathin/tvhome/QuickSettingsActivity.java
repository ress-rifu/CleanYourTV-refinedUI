package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService), and the
 * Settings pill with {@code settings_pill_panel}: a frosted glass column that slides in from the right over Home or
 * whatever app is playing. Translucent and in a task of its own, so what is behind stays on screen and Back returns
 * to it. Pressing Settings again closes the panel.
 */
public final class QuickSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        float density = getResources().getDisplayMetrics().density;
        View glass = findViewById(R.id.panel);
        glass.setBackground(new Frost.FrostDrawable(28 * density, density, 0x40000000));
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            android.widget.FrameLayout.LayoutParams position =
                    (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            int inset = position.getMarginEnd();
            position.setMarginEnd(0);
            position.setMargins(inset, position.topMargin, 0, position.bottomMargin);
            panel.setLayoutParams(position);
        }
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, pictureIntent());
        addRow(rows, R.drawable.ic_wifi, R.string.quick_network, 0, SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        addRow(rows, R.drawable.ic_settings, R.string.tv_settings, 0, SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        rows.getChildAt(0).requestFocus();
        slideIn();
    }

    /**
     * Picture and sound: the TV's own quick menu (config.xml picture_menu_component) where there is one, since it
     * opens over the screen like this panel; otherwise Android's display settings.
     */
    private Intent pictureIntent() {
        String component = getString(R.string.picture_menu_component);
        if (!component.isEmpty()) {
            ComponentName name = ComponentName.unflattenFromString(component);
            Intent menu = name == null ? null : new Intent(Intent.ACTION_MAIN).setComponent(name);
            if (menu != null && getPackageManager().resolveActivity(menu, 0) != null) return menu;
        }
        return SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS);
    }

    private static final int SLIDE_MS = 320;
    /** finish() is animating the panel out; a second Back or a pause must not start it again. */
    private boolean closing;

    private boolean reducedMotion() {
        return !getResources().getBoolean(R.bool.animations) || getResources().getBoolean(R.bool.reduced_motion);
    }

    /** In from the right edge with a fade, over a dim that fades in; laid out first, so its width is known. */
    private void slideIn() {
        View panel = findViewById(R.id.panel);
        View scrim = findViewById(R.id.scrim);
        if (reducedMotion()) return;
        scrim.getBackground().setAlpha(0);
        panel.setAlpha(0f);
        panel.post(() -> {
            float away = panel.getWidth() + 18 * getResources().getDisplayMetrics().density;
            panel.setTranslationX(away);
            panel.animate().translationX(0f).alpha(1f).setDuration(SLIDE_MS)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).withLayer().start();
            android.animation.ValueAnimator dim = android.animation.ValueAnimator.ofInt(0, 255).setDuration(SLIDE_MS);
            dim.addUpdateListener(a -> scrim.getBackground().setAlpha((int) a.getAnimatedValue()));
            dim.start();
        });
    }

    /** Back or the scrim: the panel slides back out to the right and the dim fades, then the activity ends. */
    @Override
    public void finish() {
        if (closing) return;
        closing = true;
        View panel = findViewById(R.id.panel);
        View scrim = findViewById(R.id.scrim);
        if (panel == null || reducedMotion()) {
            closeNow();
            return;
        }
        float away = panel.getWidth() + 18 * getResources().getDisplayMetrics().density;
        panel.animate().translationX(away).alpha(0f).setDuration(240)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f)).withLayer()
                .withEndAction(this::closeNow).start();
        android.animation.ValueAnimator dim = android.animation.ValueAnimator.ofInt(255, 0).setDuration(240);
        dim.addUpdateListener(a -> scrim.getBackground().setAlpha((int) a.getAnimatedValue()));
        dim.start();
    }

    private void closeNow() {
        closing = true;
        super.finish();
        overridePendingTransition(0, 0);
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver, the TV's quick menu) closes the panel at once. */
    @Override
    protected void onPause() {
        super.onPause();
        if (!isFinishing()) closeNow();
    }

    private void addRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            try {
                // The pause that follows closes the panel at once, under what opens.
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        rows.addView(row);
    }
}
