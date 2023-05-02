package com.fox2code.mmm.androidacy;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewClientCompat;
import androidx.webkit.WebViewFeature;

import com.fox2code.foxcompat.app.FoxActivity;
import com.fox2code.mmm.BuildConfig;
import com.fox2code.mmm.Constants;
import com.fox2code.mmm.MainApplication;
import com.fox2code.mmm.R;
import com.fox2code.mmm.XHooks;
import com.fox2code.mmm.utils.IntentHelper;
import com.fox2code.mmm.utils.io.net.Http;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.matomo.sdk.extra.TrackHelper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import timber.log.Timber;

/**
 * Per Androidacy repo implementation agreement, no request of this WebView shall be modified.
 */
public final class AndroidacyActivity extends FoxActivity {

    static {
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    File moduleFile;
    WebView webView;
    TextView webViewNote;
    AndroidacyWebAPI androidacyWebAPI;
    LinearProgressIndicator progressIndicator;
    boolean backOnResume;
    boolean downloadMode;

    @SuppressWarnings("deprecation")
    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface", "RestrictedApi", "ClickableViewAccessibility"})
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        this.moduleFile = new File(this.getCacheDir(), "module.zip");
        super.onCreate(savedInstanceState);
        TrackHelper.track().screen(this).with(MainApplication.getINSTANCE().getTracker());
        Intent intent = this.getIntent();
        Uri uri;
        if (!MainApplication.checkSecret(intent) || (uri = intent.getData()) == null) {
            Timber.w("Impersonation detected");
            this.forceBackPressed();
            return;
        }
        String url = uri.toString();
        if (!AndroidacyUtil.isAndroidacyLink(url, uri)) {
            Timber.w("Calling non androidacy link in secure WebView: %s", url);
            this.forceBackPressed();
            return;
        }
        if (!Http.hasWebView()) {
            Timber.w("No WebView found to load url: %s", url);
            this.forceBackPressed();
            return;
        }
        // if action bar is shown, hide it
        this.hideActionBar();
        Http.markCaptchaAndroidacySolved();
        if (!url.contains(AndroidacyUtil.REFERRER)) {
            if (url.lastIndexOf('/') < url.lastIndexOf('?')) {
                url = url + '&' + AndroidacyUtil.REFERRER;
            } else {
                url = url + '?' + AndroidacyUtil.REFERRER;
            }
        }
        // Add token to url if not present
        String token = uri.getQueryParameter("token");
        if (token == null) {
            // get from shared preferences
            url = url + "&token=" + AndroidacyRepoData.token;
        }
        // Add device_id to url if not present
        String device_id = uri.getQueryParameter("device_id");
        if (device_id == null) {
            // get from shared preferences
            device_id = AndroidacyRepoData.generateDeviceId();
            url = url + "&device_id=" + device_id;
        }
        // check if client_id is present
        String client_id = uri.getQueryParameter("client_id");
        if (client_id == null) {
            // get from shared preferences
            client_id = BuildConfig.ANDROIDACY_CLIENT_ID;
            url = url + "&client_id=" + client_id;
        }
        boolean allowInstall = intent.getBooleanExtra(Constants.EXTRA_ANDROIDACY_ALLOW_INSTALL, false);
        String title = intent.getStringExtra(Constants.EXTRA_ANDROIDACY_ACTIONBAR_TITLE);
        String config = intent.getStringExtra(Constants.EXTRA_ANDROIDACY_ACTIONBAR_CONFIG);
        int compatLevel = intent.getIntExtra(Constants.EXTRA_ANDROIDACY_COMPAT_LEVEL, 0);
        this.setContentView(R.layout.webview);
        setActionBarBackground(null);
        this.setDisplayHomeAsUpEnabled(true);
        if (title == null || title.isEmpty()) {
            this.setTitle("Androidacy");
        } else {
            this.setTitle(title);
        }
        if (allowInstall || title == null || title.isEmpty()) {
            this.hideActionBar();
        } else { // Only used for note section
            if (config != null && !config.isEmpty()) {
                String configPkg = IntentHelper.getPackageOfConfig(config);
                try {
                    XHooks.checkConfigTargetExists(this, configPkg, config);
                    this.setActionBarExtraMenuButton(R.drawable.ic_baseline_app_settings_alt_24, menu -> {
                        IntentHelper.openConfig(this, config);
                        return true;
                    });
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        this.progressIndicator = this.findViewById(R.id.progress_bar);
        this.progressIndicator.setMax(100);
        this.webView = this.findViewById(R.id.webView);
        WebSettings webSettings = this.webView.getSettings();
        webSettings.setUserAgentString(Http.getAndroidacyUA());
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(this.webView, true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setJavaScriptEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        // enable webview debugging on debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        // if app is in dark mode, force dark mode on webview
        if (MainApplication.getINSTANCE().isDarkTheme()) {
            // for api 33, use setAlgorithmicDarkeningAllowed, for api 29-32 use setForceDark, for api 28 and below use setForceDarkStrategy
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, true);
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(webSettings, WebSettingsCompat.FORCE_DARK_ON);
            } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(webSettings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
            }
        }
        // Attempt at fixing CloudFlare captcha.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            Set<String> allowList = new HashSet<>();
            allowList.add("https://*.androidacy.com");
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webSettings, allowList);
        }
        // get swipe to refresh layout
        SwipeRefreshLayout swipeRefreshLayout = this.findViewById(R.id.swipe_refresh_layout);

        this.webView.setWebViewClient(new WebViewClientCompat() {
            private String pageUrl;

            @Override
            public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
                // Don't open non Androidacy urls inside WebView
                if (request.isForMainFrame() && !AndroidacyUtil.isAndroidacyLink(request.getUrl())) {
                    if (downloadMode || backOnResume) return true;
                    // sanitize url
                    String url = request.getUrl().toString();
                    //noinspection UnnecessaryCallToStringValueOf
                    url = String.valueOf(AndroidacyUtil.hideToken(url));
                    Timber.i("Exiting WebView %s", url);
                    IntentHelper.openUri(view.getContext(), request.getUrl().toString());
                    return true;
                }
                return false;
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (AndroidacyActivity.this.megaIntercept(this.pageUrl, request.getUrl().toString())) {
                    // Block request as Androidacy doesn't allow duplicate requests
                    return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                this.pageUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressIndicator.setVisibility(View.INVISIBLE);
                progressIndicator.setProgressCompat(0, false);
            }

            private void onReceivedError(String url, int errorCode) {
                if ((url.startsWith("https://production-api.androidacy.com/magisk/") || url.startsWith("https://staging-api.androidacy.com/magisk/") || url.equals(pageUrl)) && (errorCode == 419 || errorCode == 429 || errorCode == 503)) {
                    Toast.makeText(AndroidacyActivity.this, "Too many requests!", Toast.LENGTH_LONG).show();
                    AndroidacyActivity.this.runOnUiThread(AndroidacyActivity.this::onBackPressed);
                } else if (url.equals(this.pageUrl)) {
                    postOnUiThread(() -> webViewNote.setVisibility(View.VISIBLE));
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                this.onReceivedError(failingUrl, errorCode);
            }

            @Override
            public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceErrorCompat error) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE)) {
                    this.onReceivedError(request.getUrl().toString(), error.getErrorCode());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                super.onReceivedSslError(view, handler, error);
                // log the error and url of its request
                Timber.tag("JSLog").e(error.toString());
            }
        });
        // logic for swipe to refresh
        swipeRefreshLayout.setOnRefreshListener(() -> {
            swipeRefreshLayout.setRefreshing(false);
            // reload page
            webView.reload();
        });
        this.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                FoxActivity.getFoxActivity(webView).startActivityForResult(fileChooserParams.createIntent(), (code, data) -> filePathCallback.onReceiveValue(FileChooserParams.parseResult(code, data)));
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                switch (consoleMessage.messageLevel()) {
                    case TIP -> Timber.tag("JSLog").i(consoleMessage.message());
                    case LOG -> Timber.tag("JSLog").d(consoleMessage.message());
                    case WARNING -> Timber.tag("JSLog").w(consoleMessage.message());
                    case ERROR -> Timber.tag("JSLog").e(consoleMessage.message());
                    default -> Timber.tag("JSLog").v(consoleMessage.message());
                }
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (downloadMode) return;
                if (newProgress != 100 && progressIndicator.getVisibility() != View.VISIBLE) {
                    Timber.i("Progress: %d, showing progress bar", newProgress);
                    progressIndicator.setVisibility(View.VISIBLE);
                }
                // if progress is greater than one, set indeterminate to false
                if (newProgress > 1) {
                    Timber.i("Progress: %d, setting indeterminate to false", newProgress);
                    progressIndicator.setIndeterminate(false);
                }
                progressIndicator.setProgressCompat(newProgress, true);
                if (newProgress == 100 && progressIndicator.getVisibility() != View.INVISIBLE) {
                    Timber.i("Progress: %d, hiding progress bar", newProgress);
                    progressIndicator.setIndeterminate(true);
                    progressIndicator.setVisibility(View.GONE);
                }
            }
        });
        this.webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (this.downloadMode || this.isDownloadUrl(downloadUrl)) return;
            if (AndroidacyUtil.isAndroidacyLink(downloadUrl) && !this.backOnResume) {
                AndroidacyWebAPI androidacyWebAPI = this.androidacyWebAPI;
                if (androidacyWebAPI != null) {
                    if (!androidacyWebAPI.downloadMode) {
                        // Native module popup may cause download after consumed action
                        if (androidacyWebAPI.consumedAction) return;
                        // Workaround Androidacy bug
                        final String moduleId = moduleIdOfUrl(downloadUrl);
                        if (this.megaIntercept(webView.getUrl(), downloadUrl)) {
                            // Block request as Androidacy doesn't allow duplicate requests
                            return;
                        } else if (moduleId != null) {
                            // Download module
                            Timber.i("megaIntercept failure. Forcing onBackPress");
                            this.onBackPressed();
                        }
                    }
                    androidacyWebAPI.consumedAction = true;
                    androidacyWebAPI.downloadMode = false;
                }
                this.backOnResume = true;
                Timber.i("Exiting WebView %s", AndroidacyUtil.hideToken(downloadUrl));
                for (String prefix : new String[]{"https://production-api.androidacy.com/downloads/", "https://staging-api.androidacy.com/magisk/downloads/"}) {
                    if (downloadUrl.startsWith(prefix)) {
                        return;
                    }
                }
                IntentHelper.openCustomTab(this, downloadUrl);
            }
        });
        this.androidacyWebAPI = new AndroidacyWebAPI(this, allowInstall);
        XHooks.onWebViewInitialize(this.webView, allowInstall);
        this.webView.addJavascriptInterface(this.androidacyWebAPI, "mmm");
        if (compatLevel != 0) androidacyWebAPI.notifyCompatModeRaw(compatLevel);
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept-Language", this.getResources().getConfiguration().locale.toLanguageTag());
        // set layout to view
        this.webView.loadUrl(url, headers);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (this.backOnResume) {
            this.backOnResume = false;
            this.forceBackPressed();
        } else if (this.androidacyWebAPI != null) {
            this.androidacyWebAPI.consumedAction = false;
        }
    }

    private String moduleIdOfUrl(String url) {
        for (String prefix : new String[]{"https://production-api.androidacy.com/downloads/", "https://staging-api.androidacy.com/downloads/", "https://production-api.androidacy.com/magisk/readme/", "https://staging-api.androidacy.com/magisk/readme/", "https://prodiuction-api.androidacy.com/magisk/info/", "https://staging-api.androidacy.com/magisk/info/"}) { // Make both staging and non staging act the same
            int i = url.indexOf('?', prefix.length());
            if (i == -1) i = url.length();
            if (url.startsWith(prefix)) return url.substring(prefix.length(), i);
        }
        if (this.isFileUrl(url)) {
            int i = url.indexOf("&module=");
            if (i != -1) {
                int j = url.indexOf('&', i + 1);
                if (j == -1) {
                    return url.substring(i + 8);
                } else {
                    return url.substring(i + 8, j);
                }
            }
        }
        return null;
    }

    private boolean isFileUrl(String url) {
        if (url == null) return false;
        for (String prefix : new String[]{"https://production-api.androidacy.com/downloads/", "https://staging-api.androidacy.com/downloads/"}) { // Make both staging and non staging act the same
            if (url.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean isDownloadUrl(String url) {
        for (String prefix : new String[]{"https://production-api.androidacy.com/magisk/downloads/", "https://staging-api.androidacy.com/magisk/downloads/"}) { // Make both staging and non staging act the same
            if (url.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean megaIntercept(String pageUrl, String fileUrl) {
        if (pageUrl == null || fileUrl == null) return false;
        // ensure neither pageUrl nor fileUrl are going to cause a crash
        if (pageUrl.contains(" ") || fileUrl.contains(" ")) return false;
        if (!this.isFileUrl(fileUrl)) {
            return false;
        }
        final AndroidacyWebAPI androidacyWebAPI = this.androidacyWebAPI;
        String moduleId = AndroidacyUtil.getModuleId(fileUrl);
        if (moduleId == null) {
            Timber.i("No module id?");
            // Re-open the page
            this.webView.loadUrl(pageUrl + "&force_refresh=" + System.currentTimeMillis());
        }
        String checksum = AndroidacyUtil.getChecksumFromURL(fileUrl);
        String moduleTitle = AndroidacyUtil.getModuleTitle(fileUrl);
        androidacyWebAPI.openNativeModuleDialogRaw(fileUrl, moduleId, moduleTitle, checksum, androidacyWebAPI.canInstall());
        return true;
    }

    Uri downloadFileAsync(String url) throws IOException {
        this.downloadMode = true;
        this.runOnUiThread(() -> {
            progressIndicator.setIndeterminate(false);
            progressIndicator.setVisibility(View.VISIBLE);
        });
        byte[] module;
        try {
            module = Http.doHttpGet(url, (downloaded, total, done) -> progressIndicator.setProgressCompat((downloaded * 100) / total, true));
            try (FileOutputStream fileOutputStream = new FileOutputStream(this.moduleFile)) {
                fileOutputStream.write(module);
            }
        } finally {
            //noinspection UnusedAssignment
            module = null;
            this.runOnUiThread(() -> progressIndicator.setVisibility(View.INVISIBLE));
        }
        this.backOnResume = true;
        this.downloadMode = false;
        return FileProvider.getUriForFile(this, this.getPackageName() + ".file-provider", this.moduleFile);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            SwipeRefreshLayout parent = (SwipeRefreshLayout) webView.getParent();
            parent.removeView(webView);
            webView.removeAllViews();
            webView.destroy(); // fix memory leak
        }
        Timber.i("onDestroy for %s", this);
    }
}
