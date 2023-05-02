package com.fox2code.mmm;

import com.fox2code.mmm.utils.io.Files;
import com.fox2code.mmm.utils.io.net.Http;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import timber.log.Timber;

// See https://docs.github.com/en/rest/reference/repos#releases
public class AppUpdateManager {
    public static final int FLAG_COMPAT_LOW_QUALITY = 0x0001;
    public static final int FLAG_COMPAT_NO_EXT = 0x0002;
    public static final int FLAG_COMPAT_MAGISK_CMD = 0x0004;
    public static final int FLAG_COMPAT_NEED_32BIT = 0x0008;
    public static final int FLAG_COMPAT_MALWARE = 0x0010;
    public static final int FLAG_COMPAT_NO_ANSI = 0x0020;
    public static final int FLAG_COMPAT_FORCE_ANSI = 0x0040;
    public static final int FLAG_COMPAT_FORCE_HIDE = 0x0080;
    public static final int FLAG_COMPAT_MMT_REBORN = 0x0100;
    public static final int FLAG_COMPAT_ZIP_WRAPPER = 0x0200;
    public static final String RELEASES_API_URL = "https://api.github.com/repos/Androidacy/MagiskModuleManager/releases/latest";
    private static final AppUpdateManager INSTANCE = new AppUpdateManager();
    private final HashMap<String, Integer> compatDataId = new HashMap<>();
    private final Object updateLock = new Object();
    private final File compatFile;
    private String latestRelease;
    private long lastChecked;

    private AppUpdateManager() {
        this.compatFile = new File(MainApplication.getINSTANCE().getFilesDir(), "compat.txt");
        this.latestRelease = MainApplication.getBootSharedPreferences().getString("updater_latest_release", BuildConfig.VERSION_NAME);
        this.lastChecked = 0;
        if (this.compatFile.isFile()) {
            try {
                this.parseCompatibilityFlags(new FileInputStream(this.compatFile));
            } catch (
                    IOException ignored) {
            }
        }
    }

    public static AppUpdateManager getAppUpdateManager() {
        return INSTANCE;
    }

    public static int getFlagsForModule(String moduleId) {
        return INSTANCE.getCompatibilityFlags(moduleId);
    }

    public static boolean shouldForceHide(String repoId) {
        if (BuildConfig.DEBUG || repoId.startsWith("repo_") || repoId.equals("magisk_alt_repo"))
            return false;
        return !repoId.startsWith("repo_") && (INSTANCE.getCompatibilityFlags(repoId) & FLAG_COMPAT_FORCE_HIDE) != 0;
    }

    // Return true if should show a notification
    public boolean checkUpdate(boolean force) {
        if (!BuildConfig.ENABLE_AUTO_UPDATER)
            return false;
        if (!force && this.peekShouldUpdate())
            return true;
        long lastChecked = this.lastChecked;
        if (lastChecked != 0 &&
                // Avoid spam calls by putting a 60 seconds timer
                lastChecked < System.currentTimeMillis() - 60000L)
            return force && this.peekShouldUpdate();
        synchronized (this.updateLock) {
            if (lastChecked != this.lastChecked)
                return this.peekShouldUpdate();
            try {
                JSONObject release = new JSONObject(new String(Http.doHttpGet(RELEASES_API_URL, false), StandardCharsets.UTF_8));
                String latestRelease = null;
                boolean preRelease = false;
                // get latest_release from tag_name translated to int
                if (release.has("tag_name")) {
                    latestRelease = release.getString("tag_name");
                    preRelease = release.getBoolean("prerelease");
                }
                Timber.d("Latest release: %s, isPreRelease: %s", latestRelease, preRelease);
                if (latestRelease == null)
                    return false;
                if (preRelease) {
                    this.latestRelease = "99999999"; // prevent updating to pre-release
                    return false;
                }
                this.latestRelease = latestRelease;
                this.lastChecked = System.currentTimeMillis();
            } catch (
                    Exception ioe) {
                Timber.e(ioe);
            }
        }
        return this.peekShouldUpdate();
    }

    public void checkUpdateCompat() {
        compatDataId.clear();
        try {
            Files.write(compatFile, new byte[0]);
        } catch (
                IOException e) {
            Timber.e(e);
        }
        // There once lived an implementation that used a GitHub API to get the compatibility flags. It was removed because it was too slow and the API was rate limited.
        Timber.w("Remote compatibility data flags are not implemented.");
    }

    public boolean peekShouldUpdate() {
        if (!BuildConfig.ENABLE_AUTO_UPDATER || BuildConfig.DEBUG)
            return false;
        // Convert both BuildConfig.VERSION_NAME and latestRelease to int
        int currentVersion = 0, latestVersion = 0;
        try {
            currentVersion = Integer.parseInt(BuildConfig.VERSION_NAME.replaceAll("\\D", ""));
            latestVersion = Integer.parseInt(this.latestRelease.replace("v", "").replaceAll("\\D", ""));
        } catch (
                NumberFormatException ignored) {
        }
        return currentVersion < latestVersion;
    }

    public boolean peekHasUpdate() {
        if (!BuildConfig.ENABLE_AUTO_UPDATER || BuildConfig.DEBUG)
            return false;
        return this.peekShouldUpdate();
    }

    private void parseCompatibilityFlags(InputStream inputStream) throws IOException {
        compatDataId.clear();
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;
            int i = line.indexOf('/');
            if (i == -1)
                continue;
            int value = 0;
            for (String arg : line.substring(i + 1).split(",")) {
                switch (arg) {
                    default -> {
                    }
                    case "lowQuality" -> value |= FLAG_COMPAT_LOW_QUALITY;
                    case "noExt" -> value |= FLAG_COMPAT_NO_EXT;
                    case "magiskCmd" -> value |= FLAG_COMPAT_MAGISK_CMD;
                    case "need32bit" -> value |= FLAG_COMPAT_NEED_32BIT;
                    case "malware" -> value |= FLAG_COMPAT_MALWARE;
                    case "noANSI" -> value |= FLAG_COMPAT_NO_ANSI;
                    case "forceANSI" -> value |= FLAG_COMPAT_FORCE_ANSI;
                    case "forceHide" -> value |= FLAG_COMPAT_FORCE_HIDE;
                    case "mmtReborn" -> value |= FLAG_COMPAT_MMT_REBORN;
                    case "wrapper" -> value |= FLAG_COMPAT_ZIP_WRAPPER;
                }
            }
            compatDataId.put(line.substring(0, i), value);
        }
        bufferedReader.close();
    }

    public int getCompatibilityFlags(String moduleId) {
        Integer compatFlags = compatDataId.get(moduleId);
        return compatFlags == null ? 0 : compatFlags;
    }
}
