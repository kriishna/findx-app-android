/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.webkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import org.mozilla.focus.BuildConfig;
import org.mozilla.focus.utils.AppConstants;
import org.mozilla.focus.utils.FileUtils;
import org.mozilla.focus.utils.ThreadUtils;
import org.mozilla.focus.utils.UrlUtils;
import org.mozilla.focus.utils.FinxCookie;
import org.mozilla.focus.web.BrowsingSession;
import org.mozilla.focus.web.Download;
import org.mozilla.focus.web.IWebView;
import org.mozilla.focus.web.WebViewProvider;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


public class WebkitView extends NestedWebView implements IWebView, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "WebkitView";
    private static final String KEY_CURRENTURL = "currenturl";
    private static final String KEY_STATE_UUID = "state_uuid";

    private Callback callback;
    private FocusWebViewClient client;
    private final LinkHandler linkHandler;
    FinxCookie fCookie;

    public WebkitView(Context context, AttributeSet attrs) {
        super(context, attrs);

        fCookie = new FinxCookie(context);
        client = new FocusWebViewClient(getContext().getApplicationContext());

        setWebViewClient(client);
        setWebChromeClient(createWebChromeClient());
        setDownloadListener(createDownloadListener());

        if (BuildConfig.DEBUG) {
            setWebContentsDebuggingEnabled(true);
        }

        setLongClickable(true);

        linkHandler = new LinkHandler(this);
        setOnLongClickListener(linkHandler);

        fCookie.Restore(CookieManager.getInstance());



    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        WebViewProvider.applyAppSettings(getContext(), getSettings());
    }

    @Override
    public void restoreWebViewState(Bundle inBundle) {
        final BrowsingSession session = BrowsingSession.getInstance();

        // Let's see if there's a UUID in the bundle and whether we have a state assigned to this UUID.
        final String uuid = inBundle.getString(KEY_STATE_UUID);

        final WebBackForwardList backForwardList = session.hasWebViewState(uuid)
                ? super.restoreState(session.getWebViewState(uuid))
                : null;

        // Pages are only added to the back/forward list when loading finishes. If a new page is
        // loading when the Activity is paused/killed, then that page won't be in the list,
        // and needs to be restored separately to the history list. We detect this by checking
        // whether the last fully loaded page (getCurrentItem()) matches the last page that the
        // WebView was actively loading (which was retrieved during onSaveInstanceState():
        // WebView.getUrl() always returns the currently loading or loaded page).
        // If the app is paused/killed before the initial page finished loading, then the entire
        // list will be null - so we need to additionally check whether the list even exists.

        final String desiredURL = inBundle.getString(KEY_CURRENTURL);
        client.notifyCurrentURL(desiredURL);

        if (backForwardList != null &&
                backForwardList.getCurrentItem().getUrl().equals(desiredURL)) {
            // restoreState doesn't actually load the current page, it just restores navigation history,
            // so we also need to explicitly reload in this case:
            reload();
        } else {
            loadUrl(desiredURL);
        }
    }

    @Override
    public void saveWebViewState(Bundle outState) {
        // We store the actual state into another bundle that we will keep in memory as long as this
        // browsing session is active. The data that WebView stores in this bundle is too large for
        // Android to save and restore as part of the state bundle.
        final Bundle stateData = new Bundle();
        super.saveState(stateData);

        // We generate a UUID that we will store in the bundle that Android saves and restores. The
        // actual data will be kept in memory and we are going to use the UUID to lookup the data
        // when restoring.
        final String uuid = UUID.randomUUID().toString();

        BrowsingSession.getInstance().putWebViewState(uuid, stateData);

        outState.putString(KEY_STATE_UUID, uuid);

        // See restoreWebViewState() for an explanation of why we need to save this in _addition_
        // to WebView's state
        outState.putString(KEY_CURRENTURL, getUrl());
    }

    @Override
    public void setBlockingEnabled(boolean enabled) {
        client.setBlockingEnabled(enabled);
    }

    public boolean isBlockingEnabled() {
        return client.isBlockingEnabled();
    }

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
        client.setCallback(callback);
        linkHandler.setCallback(callback);
    }

    public void loadUrl(String url) {
        // We need to check external URL handling here - shouldOverrideUrlLoading() is only
        // called by webview when clicking on a link, and not when opening a new page for the
        // first time using loadUrl().
        if (!client.shouldOverrideUrlLoading(this, url)) {

            super.loadUrl(url);
        }

        client.notifyCurrentURL(url);
    }

    @Override
    public void destroy() {
        super.destroy();

        // WebView might save data to disk once it gets destroyed. In this case our cleanup call
        // might not have been able to see this data. Let's do it again.
        deleteContentFromKnownLocations(getContext());
    }

    @Override
    public void cleanup() {
        clearFormData();
        clearHistory();
        clearMatches();
        clearSslPreferences();
        clearCache(true);

        CookieManager manager = CookieManager.getInstance();
        fCookie.Save(manager);

        manager.removeAllCookies(null);
        fCookie.Restore(manager);

        WebStorage.getInstance().deleteAllData();

        final WebViewDatabase webViewDatabase = WebViewDatabase.getInstance(getContext());
        // It isn't entirely clear how this differs from WebView.clearFormData()
        webViewDatabase.clearFormData();
        webViewDatabase.clearHttpAuthUsernamePassword();

        deleteContentFromKnownLocations(getContext());
    }

    public static void deleteContentFromKnownLocations(final Context context) {
        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                // We call all methods on WebView to delete data. But some traces still remain
                // on disk. This will wipe the whole webview directory.
                FileUtils.deleteWebViewDirectory(context);

                // WebView stores some files in the cache directory. We do not use it ourselves
                // so let's truncate it.
                FileUtils.truncateCacheDirectory(context);
            }
        });
    }

    private WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (callback != null) {
                    // This is the earliest point where we might be able to confirm a redirected
                    // URL: we don't necessarily get a shouldInterceptRequest() after a redirect,
                    // so we can only check the updated url in onProgressChanges(), or in onPageFinished()
                    // (which is even later).
                    final String viewURL = view.getUrl();
                    if (!UrlUtils.isInternalErrorURL(viewURL)) {
                        callback.onURLChanged(viewURL);
                    }
                    callback.onProgress(newProgress);
                }
            }

            @Override
            public void onShowCustomView(View view, final CustomViewCallback webviewCallback) {
                final FullscreenCallback fullscreenCallback = new FullscreenCallback() {
                    @Override
                    public void fullScreenExited() {
                        webviewCallback.onCustomViewHidden();
                    }
                };

                callback.onEnterFullScreen(fullscreenCallback, view);
            }

            @Override
            public void onHideCustomView() {
                callback.onExitFullScreen();
            }
        };
    }

    private DownloadListener createDownloadListener() {
        return new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                if (!AppConstants.supportsDownloadingFiles()) {
                    return;
                }

                final String scheme = Uri.parse(url).getScheme();
                if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                    // We are ignoring everything that is not http or https. This is a limitation of
                    // Android's download manager. There's no reason to show a download dialog for
                    // something we can't download anyways.
                    Log.w(TAG, "Ignoring download from non http(s) URL: " + url);
                    return;
                }

                if (callback != null) {
                    final Download download = new Download(url, userAgent, contentDisposition, mimetype, contentLength, Environment.DIRECTORY_DOWNLOADS);
                    callback.onDownloadStart(download);
                }
            }
        };
    }
}