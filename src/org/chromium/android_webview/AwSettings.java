// Copyright (c) 2012 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.android_webview;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.webkit.WebSettings.PluginState;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.chromium.base.CalledByNative;
import org.chromium.base.JNINamespace;
import org.chromium.base.ThreadUtils;

/**
 * Stores Android WebView specific settings that does not need to be synced to WebKit.
 * Use {@link org.chromium.content.browser.ContentSettings} for WebKit settings.
 *
 * Methods in this class can be called from any thread, including threads created by
 * the client of WebView.
 */
@JNINamespace("android_webview")
public class AwSettings {
    // This enum corresponds to WebSettings.LayoutAlgorithm. We use our own to be
    // able to extend it.
    public enum LayoutAlgorithm {
        NORMAL,
        SINGLE_COLUMN,
        NARROW_COLUMNS,
        TEXT_AUTOSIZING,
    }

    private static final String TAG = "AwSettings";

    // This class must be created on the UI thread. Afterwards, it can be
    // used from any thread. Internally, the class uses a message queue
    // to call native code on the UI thread only.

    // Lock to protect all settings.
    private final Object mAwSettingsLock = new Object();

    private final Context mContext;
    private double mDIPScale;

    private LayoutAlgorithm mLayoutAlgorithm = LayoutAlgorithm.NARROW_COLUMNS;
    private int mTextSizePercent = 100;
    private String mStandardFontFamily = "sans-serif";
    private String mFixedFontFamily = "monospace";
    private String mSansSerifFontFamily = "sans-serif";
    private String mSerifFontFamily = "serif";
    private String mCursiveFontFamily = "cursive";
    private String mFantasyFontFamily = "fantasy";
    // TODO(mnaganov): Should be obtained from Android. Problem: it is hidden.
    private String mDefaultTextEncoding = "Latin-1";
    private String mUserAgent;
    private int mMinimumFontSize = 8;
    private int mMinimumLogicalFontSize = 8;
    private int mDefaultFontSize = 16;
    private int mDefaultFixedFontSize = 13;
    private boolean mLoadsImagesAutomatically = true;
    private boolean mImagesEnabled = true;
    private boolean mJavaScriptEnabled = false;
    private boolean mAllowUniversalAccessFromFileURLs = false;
    private boolean mAllowFileAccessFromFileURLs = false;
    private boolean mJavaScriptCanOpenWindowsAutomatically = false;
    private boolean mSupportMultipleWindows = false;
    private PluginState mPluginState = PluginState.OFF;
    private boolean mAppCacheEnabled = false;
    private boolean mDomStorageEnabled = false;
    private boolean mDatabaseEnabled = false;
    private boolean mUseWideViewport = false;
    private boolean mLoadWithOverviewMode = false;
    private boolean mMediaPlaybackRequiresUserGesture = true;
    private String mDefaultVideoPosterURL;
    private float mInitialPageScalePercent = 0;

    private final boolean mSupportDeprecatedTargetDensityDPI = true;

    // Not accessed by the native side.
    private boolean mBlockNetworkLoads;  // Default depends on permission of embedding APK.
    private boolean mAllowContentUrlAccess = true;
    private boolean mAllowFileUrlAccess = true;
    private int mCacheMode = WebSettings.LOAD_DEFAULT;
    private boolean mShouldFocusFirstNode = true;
    private boolean mGeolocationEnabled = true;
    static class LazyDefaultUserAgent{
        // Lazy Holder pattern
        private static final String sInstance = nativeGetDefaultUserAgent();
    }

    // Protects access to settings global fields.
    private static final Object sGlobalContentSettingsLock = new Object();
    // For compatibility with the legacy WebView, we can only enable AppCache when the path is
    // provided. However, we don't use the path, so we just check if we have received it from the
    // client.
    private static boolean sAppCachePathIsSet = false;

    // The native side of this object.
    private int mNativeAwSettings = 0;

    // A flag to avoid sending superfluous synchronization messages.
    private boolean mIsUpdateWebkitPrefsMessagePending = false;
    // Custom handler that queues messages to call native code on the UI thread.
    private final EventHandler mEventHandler;

    private static final int MINIMUM_FONT_SIZE = 1;
    private static final int MAXIMUM_FONT_SIZE = 72;

    // Class to handle messages to be processed on the UI thread.
    private class EventHandler {
        // Message id for updating Webkit preferences
        private static final int UPDATE_WEBKIT_PREFERENCES = 0;
        // Actual UI thread handler
        private Handler mHandler;

        EventHandler() {
            mHandler = new Handler(Looper.getMainLooper()) {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                            case UPDATE_WEBKIT_PREFERENCES:
                                synchronized (mAwSettingsLock) {
                                    updateWebkitPreferencesOnUiThread();
                                    mIsUpdateWebkitPrefsMessagePending = false;
                                    mAwSettingsLock.notifyAll();
                                }
                                break;
                        }
                    }
                };
        }

        private void updateWebkitPreferencesLocked() {
            assert Thread.holdsLock(mAwSettingsLock);
            if (mNativeAwSettings == 0) return;
            if (Looper.myLooper() == mHandler.getLooper()) {
                updateWebkitPreferencesOnUiThread();
            } else {
                // We're being called on a background thread, so post a message.
                if (mIsUpdateWebkitPrefsMessagePending) {
                    return;
                }
                mIsUpdateWebkitPrefsMessagePending = true;
                mHandler.sendMessage(Message.obtain(null, UPDATE_WEBKIT_PREFERENCES));
                // We must block until the settings have been sync'd to native to
                // ensure that they have taken effect.
                try {
                    while (mIsUpdateWebkitPrefsMessagePending) {
                        mAwSettingsLock.wait();
                    }
                } catch (InterruptedException e) {}
            }
        }
    }

    public AwSettings(Context context, int nativeWebContents,
            boolean isAccessFromFileURLsGrantedByDefault) {
        ThreadUtils.assertOnUiThread();
        mContext = context;
        mBlockNetworkLoads = mContext.checkPermission(
                android.Manifest.permission.INTERNET,
                Process.myPid(),
                Process.myUid()) != PackageManager.PERMISSION_GRANTED;
        mNativeAwSettings = nativeInit(nativeWebContents);
        assert mNativeAwSettings != 0;

        if (isAccessFromFileURLsGrantedByDefault) {
            mAllowUniversalAccessFromFileURLs = true;
            mAllowFileAccessFromFileURLs = true;
        }

        mEventHandler = new EventHandler();
        mUserAgent = LazyDefaultUserAgent.sInstance;
        nativeUpdateEverything(mNativeAwSettings);
    }

    public void destroy() {
        nativeDestroy(mNativeAwSettings);
        mNativeAwSettings = 0;
    }

    public void setDIPScale(double dipScale) {
        synchronized (mAwSettingsLock) {
            mDIPScale = dipScale;
        }
    }

    public void setWebContents(int nativeWebContents) {
        synchronized (mAwSettingsLock) {
            nativeSetWebContents(mNativeAwSettings, nativeWebContents);
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setBlockNetworkLoads}.
     */
    public void setBlockNetworkLoads(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (!flag && mContext.checkPermission(
                    android.Manifest.permission.INTERNET,
                    Process.myPid(),
                    Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Permission denied - " +
                        "application missing INTERNET permission");
            }
            mBlockNetworkLoads = flag;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getBlockNetworkLoads}.
     */
    public boolean getBlockNetworkLoads() {
        synchronized (mAwSettingsLock) {
            return mBlockNetworkLoads;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAllowFileAccess}.
     */
    public void setAllowFileAccess(boolean allow) {
        synchronized (mAwSettingsLock) {
            if (mAllowFileUrlAccess != allow) {
                mAllowFileUrlAccess = allow;
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getAllowFileAccess}.
     */
    public boolean getAllowFileAccess() {
        synchronized (mAwSettingsLock) {
            return mAllowFileUrlAccess;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAllowContentAccess}.
     */
    public void setAllowContentAccess(boolean allow) {
        synchronized (mAwSettingsLock) {
            if (mAllowContentUrlAccess != allow) {
                mAllowContentUrlAccess = allow;
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getAllowContentAccess}.
     */
    public boolean getAllowContentAccess() {
        synchronized (mAwSettingsLock) {
            return mAllowContentUrlAccess;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setCacheMode}.
     */
    public void setCacheMode(int mode) {
        synchronized (mAwSettingsLock) {
            if (mCacheMode != mode) {
                mCacheMode = mode;
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getCacheMode}.
     */
    public int getCacheMode() {
        synchronized (mAwSettingsLock) {
            return mCacheMode;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setNeedInitialFocus}.
     */
    public void setShouldFocusFirstNode(boolean flag) {
        synchronized (mAwSettingsLock) {
            mShouldFocusFirstNode = flag;
        }
    }

    @Deprecated
    public void setEnableFixedLayoutMode(final boolean enable) {
        // No-op. Will be removed.
    }

    /**
     * See {@link android.webkit.WebView#setInitialScale}.
     */
    public void setInitialPageScale(final float scaleInPercent) {
        synchronized (mAwSettingsLock) {
            if (mInitialPageScalePercent != scaleInPercent) {
                mInitialPageScalePercent = scaleInPercent;
                ThreadUtils.runOnUiThreadBlocking(new Runnable() {
                    @Override
                    public void run() {
                        if (mNativeAwSettings != 0) {
                            nativeUpdateInitialPageScale(mNativeAwSettings);
                        }
                    }
                });
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setNeedInitialFocus}.
     */
    public boolean shouldFocusFirstNode() {
        synchronized(mAwSettingsLock) {
            return mShouldFocusFirstNode;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setGeolocationEnabled}.
     */
    public void setGeolocationEnabled(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mGeolocationEnabled != flag) {
                mGeolocationEnabled = flag;
            }
        }
    }

    /**
     * @return Returns if geolocation is currently enabled.
     */
    boolean getGeolocationEnabled() {
        synchronized (mAwSettingsLock) {
            return mGeolocationEnabled;
        }
    }

    /**
     * @returns the default User-Agent used by each ContentViewCore instance, i.e. unless
     * overridden by {@link #setUserAgentString()}
     */
    public static String getDefaultUserAgent() {
        return LazyDefaultUserAgent.sInstance;
    }

    /**
     * See {@link android.webkit.WebSettings#setUserAgentString}.
     */
    public void setUserAgentString(String ua) {
        synchronized (mAwSettingsLock) {
            final String oldUserAgent = mUserAgent;
            if (ua == null || ua.length() == 0) {
                mUserAgent = LazyDefaultUserAgent.sInstance;
            } else {
                mUserAgent = ua;
            }
            if (!oldUserAgent.equals(mUserAgent)) {
                ThreadUtils.runOnUiThreadBlocking(new Runnable() {
                    @Override
                    public void run() {
                        if (mNativeAwSettings != 0) {
                            nativeUpdateUserAgent(mNativeAwSettings);
                        }
                    }
                });
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getUserAgentString}.
     */
    public String getUserAgentString() {
        synchronized (mAwSettingsLock) {
            return mUserAgent;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setLoadWithOverviewMode}.
     */
    public void setLoadWithOverviewMode(boolean overview) {
        synchronized (mAwSettingsLock) {
            if (mLoadWithOverviewMode != overview) {
                mLoadWithOverviewMode = overview;
                mEventHandler.updateWebkitPreferencesLocked();
                ThreadUtils.runOnUiThreadBlocking(new Runnable() {
                    @Override
                    public void run() {
                        if (mNativeAwSettings != 0) {
                            nativeResetScrollAndScaleState(mNativeAwSettings);
                        }
                    }
                });
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getLoadWithOverviewMode}.
     */
    public boolean getLoadWithOverviewMode() {
        synchronized (mAwSettingsLock) {
            return mLoadWithOverviewMode;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setTextZoom}.
     */
    public void setTextZoom(final int textZoom) {
        synchronized (mAwSettingsLock) {
            if (mTextSizePercent != textZoom) {
                mTextSizePercent = textZoom;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getTextZoom}.
     */
    public int getTextZoom() {
        synchronized (mAwSettingsLock) {
            return mTextSizePercent;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setStandardFontFamily}.
     */
    public void setStandardFontFamily(String font) {
        synchronized (mAwSettingsLock) {
            if (font != null && !mStandardFontFamily.equals(font)) {
                mStandardFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getStandardFontFamily}.
     */
    public String getStandardFontFamily() {
        synchronized (mAwSettingsLock) {
            return mStandardFontFamily;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setFixedFontFamily}.
     */
    public void setFixedFontFamily(String font) {
        synchronized (mAwSettingsLock) {
            if (font != null && !mFixedFontFamily.equals(font)) {
                mFixedFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getFixedFontFamily}.
     */
    public String getFixedFontFamily() {
        synchronized (mAwSettingsLock) {
            return mFixedFontFamily;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setSansSerifFontFamily}.
     */
    public void setSansSerifFontFamily(String font) {
        synchronized (mAwSettingsLock) {
            if (font != null && !mSansSerifFontFamily.equals(font)) {
                mSansSerifFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getSansSerifFontFamily}.
     */
    public String getSansSerifFontFamily() {
        synchronized (mAwSettingsLock) {
            return mSansSerifFontFamily;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setSerifFontFamily}.
     */
    public void setSerifFontFamily(String font) {
        synchronized (mAwSettingsLock) {
            if (font != null && !mSerifFontFamily.equals(font)) {
                mSerifFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getSerifFontFamily}.
     */
    public String getSerifFontFamily() {
        synchronized (mAwSettingsLock) {
            return mSerifFontFamily;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setCursiveFontFamily}.
     */
    public void setCursiveFontFamily(String font) {
        synchronized (mAwSettingsLock) {
            if (font != null && !mCursiveFontFamily.equals(font)) {
                mCursiveFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getCursiveFontFamily}.
     */
    public String getCursiveFontFamily() {
        synchronized (mAwSettingsLock) {
            return mCursiveFontFamily;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setFantasyFontFamily}.
     */
    public void setFantasyFontFamily(String font) {
        synchronized (mAwSettingsLock) {
            if (font != null && !mFantasyFontFamily.equals(font)) {
                mFantasyFontFamily = font;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getFantasyFontFamily}.
     */
    public String getFantasyFontFamily() {
        synchronized (mAwSettingsLock) {
            return mFantasyFontFamily;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setMinimumFontSize}.
     */
    public void setMinimumFontSize(int size) {
        synchronized (mAwSettingsLock) {
            size = clipFontSize(size);
            if (mMinimumFontSize != size) {
                mMinimumFontSize = size;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getMinimumFontSize}.
     */
    public int getMinimumFontSize() {
        synchronized (mAwSettingsLock) {
            return mMinimumFontSize;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setMinimumLogicalFontSize}.
     */
    public void setMinimumLogicalFontSize(int size) {
        synchronized (mAwSettingsLock) {
            size = clipFontSize(size);
            if (mMinimumLogicalFontSize != size) {
                mMinimumLogicalFontSize = size;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getMinimumLogicalFontSize}.
     */
    public int getMinimumLogicalFontSize() {
        synchronized (mAwSettingsLock) {
            return mMinimumLogicalFontSize;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setDefaultFontSize}.
     */
    public void setDefaultFontSize(int size) {
        synchronized (mAwSettingsLock) {
            size = clipFontSize(size);
            if (mDefaultFontSize != size) {
                mDefaultFontSize = size;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDefaultFontSize}.
     */
    public int getDefaultFontSize() {
        synchronized (mAwSettingsLock) {
            return mDefaultFontSize;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setDefaultFixedFontSize}.
     */
    public void setDefaultFixedFontSize(int size) {
        synchronized (mAwSettingsLock) {
            size = clipFontSize(size);
            if (mDefaultFixedFontSize != size) {
                mDefaultFixedFontSize = size;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDefaultFixedFontSize}.
     */
    public int getDefaultFixedFontSize() {
        synchronized (mAwSettingsLock) {
            return mDefaultFixedFontSize;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setJavaScriptEnabled}.
     */
    public void setJavaScriptEnabled(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mJavaScriptEnabled != flag) {
                mJavaScriptEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAllowUniversalAccessFromFileURLs}.
     */
    public void setAllowUniversalAccessFromFileURLs(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mAllowUniversalAccessFromFileURLs != flag) {
                mAllowUniversalAccessFromFileURLs = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAllowFileAccessFromFileURLs}.
     */
    public void setAllowFileAccessFromFileURLs(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mAllowFileAccessFromFileURLs != flag) {
                mAllowFileAccessFromFileURLs = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setLoadsImagesAutomatically}.
     */
    public void setLoadsImagesAutomatically(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mLoadsImagesAutomatically != flag) {
                mLoadsImagesAutomatically = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getLoadsImagesAutomatically}.
     */
    public boolean getLoadsImagesAutomatically() {
        synchronized (mAwSettingsLock) {
            return mLoadsImagesAutomatically;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setImagesEnabled}.
     */
    public void setImagesEnabled(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mImagesEnabled != flag) {
                mImagesEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getImagesEnabled}.
     */
    public boolean getImagesEnabled() {
        synchronized (mAwSettingsLock) {
            return mImagesEnabled;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getJavaScriptEnabled}.
     */
    public boolean getJavaScriptEnabled() {
        synchronized (mAwSettingsLock) {
            return mJavaScriptEnabled;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getAllowUniversalAccessFromFileURLs}.
     */
    public boolean getAllowUniversalAccessFromFileURLs() {
        synchronized (mAwSettingsLock) {
            return mAllowUniversalAccessFromFileURLs;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getAllowFileAccessFromFileURLs}.
     */
    public boolean getAllowFileAccessFromFileURLs() {
        synchronized (mAwSettingsLock) {
            return mAllowFileAccessFromFileURLs;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setPluginsEnabled}.
     */
    @Deprecated
    public void setPluginsEnabled(boolean flag) {
        setPluginState(flag ? PluginState.ON : PluginState.OFF);
    }

    /**
     * See {@link android.webkit.WebSettings#setPluginState}.
     */
    public void setPluginState(PluginState state) {
        synchronized (mAwSettingsLock) {
            if (mPluginState != state) {
                mPluginState = state;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getPluginsEnabled}.
     */
    @Deprecated
    public boolean getPluginsEnabled() {
        synchronized (mAwSettingsLock) {
            return mPluginState == PluginState.ON;
        }
    }

    /**
     * Return true if plugins are disabled.
     * @return True if plugins are disabled.
     * @hide
     */
    @CalledByNative
    private boolean getPluginsDisabled() {
        // This should only be called from UpdateWebkitPreferences, which is called
        // either from the constructor, or with mAwSettingsLock being held.
        return mPluginState == PluginState.OFF;
    }

    /**
     * See {@link android.webkit.WebSettings#getPluginState}.
     */
    public PluginState getPluginState() {
        synchronized (mAwSettingsLock) {
            return mPluginState;
        }
    }


    /**
     * See {@link android.webkit.WebSettings#setJavaScriptCanOpenWindowsAutomatically}.
     */
    public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mJavaScriptCanOpenWindowsAutomatically != flag) {
                mJavaScriptCanOpenWindowsAutomatically = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getJavaScriptCanOpenWindowsAutomatically}.
     */
    public boolean getJavaScriptCanOpenWindowsAutomatically() {
        synchronized (mAwSettingsLock) {
            return mJavaScriptCanOpenWindowsAutomatically;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setLayoutAlgorithm}.
     */
    public void setLayoutAlgorithm(LayoutAlgorithm l) {
        synchronized (mAwSettingsLock) {
            if (mLayoutAlgorithm != l) {
                mLayoutAlgorithm = l;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getLayoutAlgorithm}.
     */
    public LayoutAlgorithm getLayoutAlgorithm() {
        synchronized (mAwSettingsLock) {
            return mLayoutAlgorithm;
        }
    }

    /**
     * Gets whether Text Auto-sizing layout algorithm is enabled.
     *
     * @return true if Text Auto-sizing layout algorithm is enabled
     * @hide
     */
    @CalledByNative
    private boolean getTextAutosizingEnabled() {
        return mLayoutAlgorithm == LayoutAlgorithm.TEXT_AUTOSIZING;
    }

    /**
     * See {@link android.webkit.WebSettings#setSupportMultipleWindows}.
     */
    public void setSupportMultipleWindows(boolean support) {
        synchronized (mAwSettingsLock) {
            if (mSupportMultipleWindows != support) {
                mSupportMultipleWindows = support;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#supportMultipleWindows}.
     */
    public boolean supportMultipleWindows() {
        synchronized (mAwSettingsLock) {
            return mSupportMultipleWindows;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setUseWideViewPort}.
     */
    public void setUseWideViewPort(boolean use) {
        synchronized (mAwSettingsLock) {
            if (mUseWideViewport != use) {
                mUseWideViewport = use;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getUseWideViewPort}.
     */
    public boolean getUseWideViewPort() {
        synchronized (mAwSettingsLock) {
            return mUseWideViewport;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAppCacheEnabled}.
     */
    public void setAppCacheEnabled(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mAppCacheEnabled != flag) {
                mAppCacheEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setAppCachePath}.
     */
    public void setAppCachePath(String path) {
        boolean needToSync = false;
        synchronized (sGlobalContentSettingsLock) {
            // AppCachePath can only be set once.
            if (!sAppCachePathIsSet && path != null && !path.isEmpty()) {
                sAppCachePathIsSet = true;
                needToSync = true;
            }
        }
        // The obvious problem here is that other WebViews will not be updated,
        // until they execute synchronization from Java to the native side.
        // But this is the same behaviour as it was in the legacy WebView.
        if (needToSync) {
            synchronized (mAwSettingsLock) {
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * Gets whether Application Cache is enabled.
     *
     * @return true if Application Cache is enabled
     * @hide
     */
    @CalledByNative
    private boolean getAppCacheEnabled() {
        // This should only be called from UpdateWebkitPreferences, which is called
        // either from the constructor, or with mAwSettingsLock being held.
        if (!mAppCacheEnabled) {
            return false;
        }
        synchronized (sGlobalContentSettingsLock) {
            return sAppCachePathIsSet;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setDomStorageEnabled}.
     */
    public void setDomStorageEnabled(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mDomStorageEnabled != flag) {
                mDomStorageEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDomStorageEnabled}.
     */
    public boolean getDomStorageEnabled() {
       synchronized (mAwSettingsLock) {
           return mDomStorageEnabled;
       }
    }

    /**
     * See {@link android.webkit.WebSettings#setDatabaseEnabled}.
     */
    public void setDatabaseEnabled(boolean flag) {
        synchronized (mAwSettingsLock) {
            if (mDatabaseEnabled != flag) {
                mDatabaseEnabled = flag;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDatabaseEnabled}.
     */
    public boolean getDatabaseEnabled() {
       synchronized (mAwSettingsLock) {
           return mDatabaseEnabled;
       }
    }

    /**
     * See {@link android.webkit.WebSettings#setDefaultTextEncodingName}.
     */
    public void setDefaultTextEncodingName(String encoding) {
        synchronized (mAwSettingsLock) {
            if (encoding != null && !mDefaultTextEncoding.equals(encoding)) {
                mDefaultTextEncoding = encoding;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDefaultTextEncodingName}.
     */
    public String getDefaultTextEncodingName() {
        synchronized (mAwSettingsLock) {
            return mDefaultTextEncoding;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setMediaPlaybackRequiresUserGesture}.
     */
    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        synchronized (mAwSettingsLock) {
            if (mMediaPlaybackRequiresUserGesture != require) {
                mMediaPlaybackRequiresUserGesture = require;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getMediaPlaybackRequiresUserGesture}.
     */
    public boolean getMediaPlaybackRequiresUserGesture() {
        synchronized (mAwSettingsLock) {
            return mMediaPlaybackRequiresUserGesture;
        }
    }

    /**
     * See {@link android.webkit.WebSettings#setDefaultVideoPosterURL}.
     */
    public void setDefaultVideoPosterURL(String url) {
        synchronized (mAwSettingsLock) {
            if (mDefaultVideoPosterURL != null && !mDefaultVideoPosterURL.equals(url) ||
                    mDefaultVideoPosterURL == null && url != null) {
                mDefaultVideoPosterURL = url;
                mEventHandler.updateWebkitPreferencesLocked();
            }
        }
    }

    /**
     * See {@link android.webkit.WebSettings#getDefaultVideoPosterURL}.
     */
    public String getDefaultVideoPosterURL() {
        synchronized (mAwSettingsLock) {
            return mDefaultVideoPosterURL;
        }
    }

    private int clipFontSize(int size) {
        if (size < MINIMUM_FONT_SIZE) {
            return MINIMUM_FONT_SIZE;
        } else if (size > MAXIMUM_FONT_SIZE) {
            return MAXIMUM_FONT_SIZE;
        }
        return size;
    }

    private void updateWebkitPreferencesOnUiThread() {
        if (mNativeAwSettings != 0) {
            ThreadUtils.assertOnUiThread();
            nativeUpdateWebkitPreferences(mNativeAwSettings);
        }
    }

    private native int nativeInit(int webContentsPtr);

    private native void nativeDestroy(int nativeAwSettings);

    private native void nativeResetScrollAndScaleState(int nativeAwSettings);

    private native void nativeSetWebContents(int nativeAwSettings, int nativeWebContents);

    private native void nativeUpdateEverything(int nativeAwSettings);

    private native void nativeUpdateInitialPageScale(int nativeAwSettings);

    private native void nativeUpdateUserAgent(int nativeAwSettings);

    private native void nativeUpdateWebkitPreferences(int nativeAwSettings);

    private static native String nativeGetDefaultUserAgent();
}
