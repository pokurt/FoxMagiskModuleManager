package com.fox2code.mmm.repo;

import android.net.Uri;

import androidx.annotation.NonNull;

import com.fox2code.mmm.AppUpdateManager;
import com.fox2code.mmm.BuildConfig;
import com.fox2code.mmm.MainActivity;
import com.fox2code.mmm.MainApplication;
import com.fox2code.mmm.R;
import com.fox2code.mmm.XRepo;
import com.fox2code.mmm.manager.ModuleInfo;
import com.fox2code.mmm.utils.io.Files;
import com.fox2code.mmm.utils.io.PropUtils;
import com.fox2code.mmm.utils.realm.ModuleListCache;
import com.fox2code.mmm.utils.realm.ReposList;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.realm.Realm;
import io.realm.RealmConfiguration;
import io.realm.RealmResults;
import timber.log.Timber;

public class RepoData extends XRepo {
    public final JSONObject supportedProperties = new JSONObject();
    private final Object populateLock = new Object();

    public String url;

    public String id;
    public File cacheRoot;
    public HashMap<String, RepoModule> moduleHashMap;
    public JSONObject metaDataCache;
    public long lastUpdate;
    public String name, website, support, donate, submitModule;
    protected String defaultName, defaultWebsite, defaultSupport, defaultDonate, defaultSubmitModule;

    // array with module info default values
    // supported properties for a module
    //id=<string>
    //name=<string>
    //version=<string>
    //versionCode=<int>
    //author=<string>
    //description=<string>
    //minApi=<int>
    //maxApi=<int>
    //minMagisk=<int>
    //needRamdisk=<boolean>
    //support=<url>
    //donate=<url>
    //config=<package>
    //changeBoot=<boolean>
    //mmtReborn=<boolean>
    // extra properties only useful for the database
    //repoId=<string>
    //installed=<boolean>
    //installedVersionCode=<int> (only if installed)
    private boolean forceHide, enabled; // Cache for speed

    public RepoData(String url, File cacheRoot) {
        // setup supportedProperties
        try {
            supportedProperties.put("id", "");
            supportedProperties.put("name", "");
            supportedProperties.put("version", "");
            supportedProperties.put("versionCode", "");
            supportedProperties.put("author", "");
            supportedProperties.put("description", "");
            supportedProperties.put("minApi", "");
            supportedProperties.put("maxApi", "");
            supportedProperties.put("minMagisk", "");
            supportedProperties.put("needRamdisk", "");
            supportedProperties.put("support", "");
            supportedProperties.put("donate", "");
            supportedProperties.put("config", "");
            supportedProperties.put("changeBoot", "");
            supportedProperties.put("mmtReborn", "");
            supportedProperties.put("repoId", "");
            supportedProperties.put("installed", "");
            supportedProperties.put("installedVersionCode", "");
            supportedProperties.put("safe", "");
        } catch (JSONException e) {
            Timber.e(e, "Error while setting up supportedProperties");
        }
        this.url = url;
        this.id = RepoManager.internalIdOfUrl(url);
        this.cacheRoot = cacheRoot;
        // metadata cache is a realm database from ModuleListCache
        this.metaDataCache = null;
        this.moduleHashMap = new HashMap<>();
        this.defaultName = url; // Set url as default name
        this.forceHide = AppUpdateManager.shouldForceHide(this.id);
        // this.enable is set from the database
        RealmConfiguration realmConfiguration = new RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(MainApplication.getINSTANCE().getKey()).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(MainApplication.getINSTANCE().getDataDirWithPath("realms")).schemaVersion(1).build();
        Realm realm = Realm.getInstance(realmConfiguration);
        ReposList reposList = realm.where(ReposList.class).equalTo("id", this.id).findFirst();
        if (reposList == null) {
            Timber.d("RepoData for %s not found in database", this.id);
            // log every repo in db
            Object[] fullList = realm.where(ReposList.class).findAll().toArray();
            Timber.d("RepoData: " + this.id + ". repos in database: " + fullList.length);
            for (Object repo : fullList) {
                ReposList r = (ReposList) repo;
                Timber.d("RepoData: " + this.id + ". repo: " + r.getId() + " " + r.getName() + " " + r.getWebsite() + " " + r.getSupport() + " " + r.getDonate() + " " + r.getSubmitModule() + " " + r.isEnabled());
            }
        } else {
            Timber.d("RepoData for %s found in database", this.id);
        }
        Timber.d("RepoData: " + this.id + ". record in database: " + (reposList != null ? reposList.toString() : "none"));
        this.enabled = (!this.forceHide && reposList != null && reposList.isEnabled());
        this.defaultWebsite = "https://" + Uri.parse(url).getHost() + "/";
        // open realm database
        // load metadata from realm database
        if (this.enabled) {
            try {
                this.metaDataCache = ModuleListCache.getRepoModulesAsJson(this.id);
                // log count of modules in the database
                Timber.d("RepoData: " + this.id + ". modules in database: " + this.metaDataCache.length());
                // load repo metadata from ReposList unless it's a built-in repo
                if (RepoManager.isBuiltInRepo(this.id)) {
                    this.name = this.defaultName;
                    this.website = this.defaultWebsite;
                    this.support = this.defaultSupport;
                    this.donate = this.defaultDonate;
                    this.submitModule = this.defaultSubmitModule;
                } else {
                    // get everything from ReposList realm database
                    this.name = Objects.requireNonNull(realm.where(ReposList.class).equalTo("id", this.id).findFirst()).getName();
                    this.website = Objects.requireNonNull(realm.where(ReposList.class).equalTo("id", this.id).findFirst()).getWebsite();
                    this.support = Objects.requireNonNull(realm.where(ReposList.class).equalTo("id", this.id).findFirst()).getSupport();
                    this.donate = Objects.requireNonNull(realm.where(ReposList.class).equalTo("id", this.id).findFirst()).getDonate();
                    this.submitModule = Objects.requireNonNull(realm.where(ReposList.class).equalTo("id", this.id).findFirst()).getSubmitModule();
                }
            } catch (Exception e) {
                Timber.w("Failed to load repo metadata from database: " + e.getMessage() + ". If this is a first time run, this is normal.");
            }
        }
        realm.close();
    }

    private static boolean isNonNull(String str) {
        return str != null && !str.isEmpty() && !"null".equals(str);
    }

    protected boolean prepare() {
        return true;
    }

    protected List<RepoModule> populate(JSONObject jsonObject) throws JSONException {
        List<RepoModule> newModules = new ArrayList<>();
        synchronized (this.populateLock) {
            String name = jsonObject.getString("name").trim();
            // if Official is present, remove it, or (Official), or [Official]. We don't want to show it in the UI
            String nameForModules = name.endsWith(" (Official)") ? name.substring(0, name.length() - 11) : name;
            nameForModules = nameForModules.endsWith(" [Official]") ? nameForModules.substring(0, nameForModules.length() - 11) : nameForModules;
            nameForModules = nameForModules.contains("Official") ? nameForModules.replace("Official", "").trim() : nameForModules;
            long lastUpdate = jsonObject.getLong("last_update");
            for (RepoModule repoModule : this.moduleHashMap.values()) {
                repoModule.processed = false;
            }
            JSONArray array = jsonObject.getJSONArray("modules");
            int len = array.length();
            for (int i = 0; i < len; i++) {
                JSONObject module = array.getJSONObject(i);
                String moduleId = module.getString("id");
                // module IDs must match the regex ^[a-zA-Z][a-zA-Z0-9._-]+$ and cannot be empty or null or equal ak3-helper
                if (moduleId.isEmpty() || moduleId.equals("ak3-helper") || !moduleId.matches("^[a-zA-Z][a-zA-Z0-9._-]+$")) {
                    continue;
                }
                // If module id start with a dot, warn user
                if (moduleId.charAt(0) == '.') {
                    Timber.w("This is not recommended and may indicate an attempt to hide the module");
                }
                long moduleLastUpdate = module.getLong("last_update");
                String moduleNotesUrl = module.getString("notes_url");
                String modulePropsUrl = module.getString("prop_url");
                String moduleZipUrl = module.getString("zip_url");
                String moduleChecksum = module.optString("checksum");
                String moduleStars = module.optString("stars");
                String moduleDownloads = module.optString("downloads");
                // if downloads is mull or empty, try to get it from the stats field
                if (moduleDownloads.isEmpty() && module.has("stats")) {
                    moduleDownloads = module.optString("stats");
                }
                RepoModule repoModule = this.moduleHashMap.get(moduleId);
                if (repoModule == null) {
                    repoModule = new RepoModule(this, moduleId);
                    this.moduleHashMap.put(moduleId, repoModule);
                    newModules.add(repoModule);
                } else {
                    if (repoModule.lastUpdated < moduleLastUpdate || repoModule.moduleInfo.hasFlag(ModuleInfo.FLAG_METADATA_INVALID)) {
                        newModules.add(repoModule);
                    }
                }
                repoModule.processed = true;
                repoModule.repoName = nameForModules;
                repoModule.lastUpdated = moduleLastUpdate;
                repoModule.notesUrl = moduleNotesUrl;
                repoModule.propUrl = modulePropsUrl;
                repoModule.zipUrl = moduleZipUrl;
                repoModule.checksum = moduleChecksum;
                // safety check must be overridden per repo. only androidacy repo has this flag currently
                // repoModule.safe = module.optBoolean("safe", false);
                if (!moduleStars.isEmpty()) {
                    try {
                        repoModule.qualityValue = Integer.parseInt(moduleStars);
                        repoModule.qualityText = R.string.module_stars;
                    } catch (NumberFormatException ignored) {
                    }
                } else if (!moduleDownloads.isEmpty()) {
                    try {
                        repoModule.qualityValue = Integer.parseInt(moduleDownloads);
                        repoModule.qualityText = R.string.module_downloads;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            // Remove no longer existing modules
            Iterator<RepoModule> moduleInfoIterator = this.moduleHashMap.values().iterator();
            while (moduleInfoIterator.hasNext()) {
                RepoModule repoModule = moduleInfoIterator.next();
                if (!repoModule.processed) {
                    boolean delete = new File(this.cacheRoot, repoModule.id + ".prop").delete();
                    if (!delete) {
                        throw new RuntimeException("Failed to delete module metadata");
                    }
                    moduleInfoIterator.remove();
                } else {
                    repoModule.moduleInfo.verify();
                }
            }
            // Update final metadata
            this.name = name;
            this.lastUpdate = lastUpdate;
            this.website = jsonObject.optString("website");
            this.support = jsonObject.optString("support");
            this.donate = jsonObject.optString("donate");
            this.submitModule = jsonObject.optString("submitModule");
        }
        return newModules;
    }

    @Override
    public boolean isEnabledByDefault() {
        return BuildConfig.ENABLED_REPOS.contains(this.id);
    }

    public void storeMetadata(RepoModule repoModule, byte[] data) throws IOException {
        Files.write(new File(this.cacheRoot, repoModule.id + ".prop"), data);
    }

    public boolean tryLoadMetadata(RepoModule repoModule) {
        File file = new File(this.cacheRoot, repoModule.id + ".prop");
        if (file.exists()) {
            try {
                ModuleInfo moduleInfo = repoModule.moduleInfo;
                PropUtils.readProperties(moduleInfo, file.getAbsolutePath(), repoModule.repoName + "/" + moduleInfo.name, false);
                moduleInfo.flags &= ~ModuleInfo.FLAG_METADATA_INVALID;
                if (moduleInfo.version == null) {
                    moduleInfo.version = "v" + moduleInfo.versionCode;
                }
                return true;
            } catch (Exception ignored) {
                boolean delete = file.delete();
                if (!delete) {
                    throw new RuntimeException("Failed to delete invalid metadata file");
                }
            }
        } else {
            Timber.d("Metadata file not found for %s", repoModule.id);
        }
        repoModule.moduleInfo.flags |= ModuleInfo.FLAG_METADATA_INVALID;
        return false;
    }

    @Override
    public boolean isEnabled() {
        RealmConfiguration realmConfiguration2 = new RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(MainApplication.getINSTANCE().getKey()).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(MainApplication.getINSTANCE().getDataDirWithPath("realms")).schemaVersion(1).build();
        Realm realm2 = Realm.getInstance(realmConfiguration2);
        AtomicBoolean dbEnabled = new AtomicBoolean(false);
        realm2.executeTransaction(realm -> {
            ReposList reposList = realm.where(ReposList.class).equalTo("id", this.id).findFirst();
            if (reposList != null) {
                dbEnabled.set(reposList.isEnabled());
            } else {
                // should never happen but for safety
                dbEnabled.set(false);
            }
        });
        realm2.close();
        if (dbEnabled.get()) {
            return !this.forceHide;
        } else {
            return false;
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled && !this.forceHide;
        // reposlist realm
        RealmConfiguration realmConfiguration2 = new RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(MainApplication.getINSTANCE().getKey()).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(MainApplication.getINSTANCE().getDataDirWithPath("realms")).schemaVersion(1).build();
        Realm realm2 = Realm.getInstance(realmConfiguration2);
        realm2.executeTransaction(realm -> {
            ReposList reposList = realm.where(ReposList.class).equalTo("id", this.id).findFirst();
            if (reposList != null) {
                reposList.setEnabled(enabled);
            }
        });
        realm2.close();
    }

    public void updateEnabledState() {
        // Make sure first_launch preference is set to false
        if (MainActivity.doSetupNowRunning) {
            return;
        }
        if (this.id == null) {
            Timber.e("Repo ID is null");
            return;
        }
        // if repo starts with repo_, it's always enabled bc custom repos can't be disabled without being deleted.
        this.forceHide = AppUpdateManager.shouldForceHide(this.id);
        // reposlist realm
        RealmConfiguration realmConfiguration2 = new RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(MainApplication.getINSTANCE().getKey()).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(MainApplication.getINSTANCE().getDataDirWithPath("realms")).schemaVersion(1).build();
        Realm realm2 = Realm.getInstance(realmConfiguration2);
        boolean dbEnabled = false;
        try {
            dbEnabled = Objects.requireNonNull(realm2.where(ReposList.class).equalTo("id", this.id).findFirst()).isEnabled();
        } catch (Exception e) {
            Timber.e(e, "Error while updating enabled state for repo %s", this.id);
        }
        realm2.close();
        this.enabled = (!this.forceHide) && dbEnabled;
    }

    public String getUrl() {
        return this.url;
    }

    public String getPreferenceId() {
        return this.id;
    }

    // Repo data info getters
    @NonNull
    @Override
    public String getName() {
        if (isNonNull(this.name)) return this.name;
        if (this.defaultName != null) return this.defaultName;
        return this.url;
    }

    @NonNull
    public String getWebsite() {
        if (isNonNull(this.website)) return this.website;
        if (this.defaultWebsite != null) return this.defaultWebsite;
        return this.url;
    }

    public String getSupport() {
        if (isNonNull(this.support)) return this.support;
        return this.defaultSupport;
    }

    public String getDonate() {
        if (isNonNull(this.donate)) return this.donate;
        return this.defaultDonate;
    }

    public String getSubmitModule() {
        if (isNonNull(this.submitModule)) return this.submitModule;
        return this.defaultSubmitModule;
    }

    public final boolean isForceHide() {
        return this.forceHide;
    }

    // should update (lastUpdate > 15 minutes)
    public boolean shouldUpdate() {
        Timber.d("Repo " + this.id + " should update check called");
        RealmConfiguration realmConfiguration2 = new RealmConfiguration.Builder().name("ReposList.realm").encryptionKey(MainApplication.getINSTANCE().getKey()).allowQueriesOnUiThread(true).allowWritesOnUiThread(true).directory(MainApplication.getINSTANCE().getDataDirWithPath("realms")).schemaVersion(1).build();
        Realm realm2 = Realm.getInstance(realmConfiguration2);
        ReposList repo = realm2.where(ReposList.class).equalTo("id", this.id).findFirst();
        // Make sure ModuleListCache for repoId is not null
        File cacheRoot = MainApplication.getINSTANCE().getDataDirWithPath("realms/repos/" + this.id);
        RealmConfiguration realmConfiguration = new RealmConfiguration.Builder().name("ModuleListCache.realm").encryptionKey(MainApplication.getINSTANCE().getKey()).schemaVersion(1).deleteRealmIfMigrationNeeded().allowWritesOnUiThread(true).allowQueriesOnUiThread(true).directory(cacheRoot).build();
        Realm realm = Realm.getInstance(realmConfiguration);
        RealmResults<ModuleListCache> moduleListCache = realm.where(ModuleListCache.class).equalTo("repoId", this.id).findAll();
        if (repo != null) {
            if (repo.getLastUpdate() != 0 && moduleListCache.size() != 0) {
                long lastUpdate = repo.getLastUpdate();
                long currentTime = System.currentTimeMillis();
                long diff = currentTime - lastUpdate;
                long diffMinutes = diff / (60 * 1000) % 60;
                Timber.d("Repo " + this.id + " updated: " + diffMinutes + " minutes ago");
                realm.close();
                return diffMinutes > (BuildConfig.DEBUG ? 15 : 30);
            } else {
                Timber.d("Repo " + this.id + " should update could not find repo in database");
                Timber.d("This is probably an error, please report this to the developer");
                realm.close();
                return true;
            }
        } else {
            realm.close();
        }
        return true;
    }
}
