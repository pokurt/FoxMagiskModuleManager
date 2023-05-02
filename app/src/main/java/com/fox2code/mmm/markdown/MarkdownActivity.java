package com.fox2code.mmm.markdown;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.fox2code.foxcompat.app.FoxActivity;
import com.fox2code.mmm.Constants;
import com.fox2code.mmm.MainApplication;
import com.fox2code.mmm.R;
import com.fox2code.mmm.XHooks;
import com.fox2code.mmm.utils.IntentHelper;
import com.fox2code.mmm.utils.io.net.Http;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.topjohnwu.superuser.internal.UiThreadHandler;

import org.matomo.sdk.extra.TrackHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import timber.log.Timber;

public class MarkdownActivity extends FoxActivity {
    private static final HashMap<String, String> redirects = new HashMap<>(4);
    private static final String[] variants = new String[]{"readme.md", "README.MD", ".github/README.md"};
    private TextView header;
    private TextView footer;

    private static byte[] getRawMarkdown(String url) throws IOException {
        String newUrl = redirects.get(url);
        if (newUrl != null && !newUrl.equals(url)) {
            return Http.doHttpGet(newUrl, true);
        }
        try {
            return Http.doHttpGet(url, true);
        } catch (IOException e) {
            // Workaround GitHub README.md case sensitivity issue
            if (url.startsWith("https://raw.githubusercontent.com/") && url.endsWith("/README.md")) {
                String prefix = url.substring(0, url.length() - 9);
                for (String suffix : variants) {
                    newUrl = prefix + suffix;
                    try { // Try with lowercase version
                        byte[] rawMarkdown = Http.doHttpGet(prefix + suffix, true);
                        redirects.put(url, newUrl); // Avoid retries
                        return rawMarkdown;
                    } catch (IOException ignored) {
                    }
                }
            }
            throw e;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TrackHelper.track().screen(this).with(MainApplication.getINSTANCE().getTracker());
        this.setDisplayHomeAsUpEnabled(true);
        Intent intent = this.getIntent();
        if (!MainApplication.checkSecret(intent)) {
            Timber.e("Impersonation detected!");
            this.forceBackPressed();
            return;
        }
        String url = intent.getExtras().getString(Constants.EXTRA_MARKDOWN_URL);
        String title = intent.getExtras().getString(Constants.EXTRA_MARKDOWN_TITLE);
        String config = intent.getExtras().getString(Constants.EXTRA_MARKDOWN_CONFIG);
        boolean change_boot = intent.getExtras().getBoolean(Constants.EXTRA_MARKDOWN_CHANGE_BOOT);
        boolean needs_ramdisk = intent.getExtras().getBoolean(Constants.EXTRA_MARKDOWN_NEEDS_RAMDISK);
        int min_magisk = intent.getExtras().getInt(Constants.EXTRA_MARKDOWN_MIN_MAGISK);
        int min_api = intent.getExtras().getInt(Constants.EXTRA_MARKDOWN_MIN_API);
        int max_api = intent.getExtras().getInt(Constants.EXTRA_MARKDOWN_MAX_API);
        if (title != null && !title.isEmpty()) {
            this.setTitle(title);
        }
        setActionBarBackground(null);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION, 0);
        if (config != null && !config.isEmpty()) {
            String configPkg = IntentHelper.getPackageOfConfig(config);
            try {
                XHooks.checkConfigTargetExists(this, configPkg, config);
                this.setActionBarExtraMenuButton(R.drawable.ic_baseline_app_settings_alt_24, menu -> {
                    IntentHelper.openConfig(this, config);
                    return true;
                });
            } catch (PackageManager.NameNotFoundException e) {
                Timber.w("Config package \"" + configPkg + "\" missing for markdown view");
            }
        }
        // validate the url won't crash the app
        if (url == null || url.isEmpty() || url.contains("..")) {
            Timber.e("Invalid url %s", String.valueOf(url));
            this.forceBackPressed();
            return;
        }
        //noinspection UnnecessaryCallToStringValueOf
        Timber.i("Url for markdown %s", String.valueOf(url));
        setContentView(R.layout.markdown_view);
        final ViewGroup markdownBackground = findViewById(R.id.markdownBackground);
        final TextView textView = findViewById(R.id.markdownView);
        this.header = findViewById(R.id.markdownHeader);
        this.footer = findViewById(R.id.markdownFooter);
        this.updateBlurState();
        UiThreadHandler.handler.post(() -> // Fix header/footer height
                this.updateScreenInsets(this.getResources().getConfiguration()));

        // Really bad created (MSG by Der_Googler)
        // set "message" to null to disable dialog
        if (change_boot) this.addChip(MarkdownChip.CHANGE_BOOT);
        if (needs_ramdisk) this.addChip(MarkdownChip.NEED_RAMDISK);
        if (min_magisk != 0) this.addChip(MarkdownChip.MIN_MAGISK, String.valueOf(min_magisk));
        if (min_api != 0) this.addChip(MarkdownChip.MIN_SDK, parseAndroidVersion(min_api));
        if (max_api != 0) this.addChip(MarkdownChip.MAX_SDK, parseAndroidVersion(max_api));

        new Thread(() -> {
            try {
                Timber.i("Downloading");
                byte[] rawMarkdown = getRawMarkdown(url);
                Timber.i("Encoding");
                String markdown = new String(rawMarkdown, StandardCharsets.UTF_8);
                Timber.i("Done!");
                runOnUiThread(() -> {
                    findViewById(R.id.markdownFooter).setMinimumHeight(this.getNavigationBarHeight());
                    MainApplication.getINSTANCE().getMarkwon().setMarkdown(textView, MarkdownUrlLinker.urlLinkify(markdown));
                    if (markdownBackground != null) {
                        markdownBackground.setClickable(true);
                    }
                });
            } catch (Exception e) {
                Timber.e(e);
                runOnUiThread(() -> Toast.makeText(this, R.string.failed_download, Toast.LENGTH_SHORT).show());
            }
        }, "Markdown load thread").start();
    }

    private void updateBlurState() {
        if (MainApplication.isBlurEnabled()) {
            // set bottom navigation bar color to transparent blur
            BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
            bottomNavigationView.setBackgroundColor(Color.TRANSPARENT);
            bottomNavigationView.setAlpha(0.8F);
            // set dialogs to have transparent blur
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
    }

    private void updateScreenInsets() {
        this.runOnUiThread(() -> this.updateScreenInsets(this.getResources().getConfiguration()));
    }

    private void updateScreenInsets(Configuration configuration) {
        boolean landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int bottomInset = (landscape ? 0 : this.getNavigationBarHeight());
        int statusBarHeight = getStatusBarHeight();
        int actionBarHeight = getActionBarHeight();
        int combinedBarsHeight = statusBarHeight + actionBarHeight;
        this.header.setMinHeight(combinedBarsHeight);
        this.footer.setMinHeight(bottomInset);
        //this.actionBarBlur.invalidate();
    }

    @Override
    public void refreshUI() {
        super.refreshUI();
        this.updateScreenInsets();
        this.updateBlurState();
    }

    @Override
    protected void onWindowUpdated() {
        this.updateScreenInsets();
    }

    private void addChip(MarkdownChip markdownChip) {
        this.makeChip(this.getString(markdownChip.title), markdownChip.desc == 0 ? null : this.getString(markdownChip.desc));
    }

    private void addChip(MarkdownChip markdownChip, String extra) {
        String title = this.getString(markdownChip.title);
        if (title.contains("%s")) {
            title = title.replace("%s", extra);
        } else {
            title = title + " " + extra;
        }
        this.makeChip(title, markdownChip.desc == 0 ? null : this.getString(markdownChip.desc));
    }

    private void makeChip(String title, String message) {
        final ChipGroup chip_group_holder = findViewById(R.id.chip_group_holder);
        Chip chip = new Chip(this);
        chip.setText(title);
        chip.setVisibility(View.VISIBLE);
        if (message != null) {
            chip.setOnClickListener(_view -> {
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);

                builder.setTitle(title).setMessage(message).setCancelable(true).setPositiveButton(R.string.ok, (x, y) -> x.dismiss()).show();

            });
        }
        chip_group_holder.addView(chip);
    }

    private String parseAndroidVersion(int version) {
        return switch (version) {
            case Build.VERSION_CODES.JELLY_BEAN -> "4.1 JellyBean";
            case Build.VERSION_CODES.JELLY_BEAN_MR1 -> "4.2 JellyBean";
            case Build.VERSION_CODES.JELLY_BEAN_MR2 -> "4.3 JellyBean";
            case Build.VERSION_CODES.KITKAT -> "4.4 KitKat";
            case Build.VERSION_CODES.KITKAT_WATCH -> "4.4 KitKat Watch";
            case Build.VERSION_CODES.LOLLIPOP -> "5.0 Lollipop";
            case Build.VERSION_CODES.LOLLIPOP_MR1 -> "5.1 Lollipop";
            case Build.VERSION_CODES.M -> "6.0 Marshmallow";
            case Build.VERSION_CODES.N -> "7.0 Nougat";
            case Build.VERSION_CODES.N_MR1 -> "7.1 Nougat";
            case Build.VERSION_CODES.O -> "8.0 Oreo";
            case Build.VERSION_CODES.O_MR1 -> "8.1 Oreo";
            case Build.VERSION_CODES.P -> "9.0 Pie";
            case Build.VERSION_CODES.Q -> "10 (Q)";
            case Build.VERSION_CODES.R -> "11 (R)";
            case Build.VERSION_CODES.S -> "12 (S)";
            case Build.VERSION_CODES.S_V2 -> "12L";
            case Build.VERSION_CODES.TIRAMISU -> "13 Tiramisu";
            default -> "Sdk: " + version;
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        View footer = findViewById(R.id.markdownFooter);
        if (footer != null) footer.setMinimumHeight(this.getNavigationBarHeight());
    }
}
