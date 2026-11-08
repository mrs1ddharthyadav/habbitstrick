package com.habbitstrick.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.Executor;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends AppCompatActivity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final String KS_ALIAS = "hs_bio_key";

    private WebView webView;
    private String pendingBackup = "";
    private ActivityResultLauncher<String> createDoc;
    private ActivityResultLauncher<String[]> openDoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        createDoc = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                    if (uri == null) {
                        js("window.nativeBackupSaved&&window.nativeBackupSaved(false)");
                        return;
                    }
                    boolean ok = false;
                    try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                        if (os != null) {
                            os.write(pendingBackup.getBytes(StandardCharsets.UTF_8));
                            ok = true;
                        }
                    } catch (Exception ignored) {
                    }
                    pendingBackup = "";
                    js("window.nativeBackupSaved&&window.nativeBackupSaved(" + ok + ")");
                });

        openDoc = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) return;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > 5 * 1024 * 1024) return;
                    bos.write(buf, 0, n);
                }
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                js("window.nativeBackupLoaded&&window.nativeBackupLoaded(" + JSONObject.quote(text) + ")");
            } catch (Exception ignored) {
            }
        });

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        webView.addJavascriptInterface(new Bridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        Reminders.createChannel(this);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
                "(function(){return window.nativeBack?window.nativeBack():false;})()",
                value -> {
                    if (!"true".equals(value)) {
                        moveTaskToBack(true);
                    }
                });
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private void js(final String script) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    /* ---------- biometric helpers ---------- */

    private SharedPreferences prefs() {
        return Reminders.prefs(this);
    }

    private boolean bioAvailableNow() {
        try {
            return BiometricManager.from(this)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    private void deleteKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KS_ALIAS)) ks.deleteEntry(KS_ALIAS);
        } catch (Exception ignored) {
        }
    }

    private SecretKey createKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            b.setUserAuthenticationValidityDurationSeconds(-1);
        }
        kg.init(b.build());
        return kg.generateKey();
    }

    private SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return (SecretKey) ks.getKey(KS_ALIAS, null);
    }

    private BiometricPrompt.PromptInfo promptInfo(String title) {
        return new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Habbit Strick")
                .setNegativeButtonText("Use password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
    }

    private void doBioEnable(final String pw) {
        try {
            deleteKey();
            SecretKey key = createKey();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            Executor ex = ContextCompat.getMainExecutor(this);
            BiometricPrompt bp = new BiometricPrompt(this, ex, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    try {
                        Cipher ci = result.getCryptoObject().getCipher();
                        byte[] ct = ci.doFinal(pw.getBytes(StandardCharsets.UTF_8));
                        byte[] iv = ci.getIV();
                        prefs().edit()
                                .putString("bioCt", Base64.encodeToString(ct, Base64.NO_WRAP))
                                .putString("bioIv", Base64.encodeToString(iv, Base64.NO_WRAP))
                                .apply();
                        js("window.nativeBioEnabled&&window.nativeBioEnabled(true,'')");
                    } catch (Exception e) {
                        deleteKey();
                        js("window.nativeBioEnabled&&window.nativeBioEnabled(false,'Could not turn it on.')");
                    }
                }

                @Override
                public void onAuthenticationError(int code, CharSequence err) {
                    deleteKey();
                    js("window.nativeBioEnabled&&window.nativeBioEnabled(false," + JSONObject.quote(String.valueOf(err)) + ")");
                }
            });
            bp.authenticate(promptInfo("Turn on fingerprint unlock"), new BiometricPrompt.CryptoObject(c));
        } catch (Exception e) {
            deleteKey();
            js("window.nativeBioEnabled&&window.nativeBioEnabled(false,'Fingerprint is not available on this phone.')");
        }
    }

    private void doBioUnlock() {
        String ctS = prefs().getString("bioCt", null);
        String ivS = prefs().getString("bioIv", null);
        if (ctS == null || ivS == null) {
            js("window.nativeBio&&window.nativeBio(false,'','')");
            return;
        }
        try {
            final byte[] ct = Base64.decode(ctS, Base64.NO_WRAP);
            byte[] iv = Base64.decode(ivS, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
            Executor ex = ContextCompat.getMainExecutor(this);
            BiometricPrompt bp = new BiometricPrompt(this, ex, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    try {
                        Cipher ci = result.getCryptoObject().getCipher();
                        String pw = new String(ci.doFinal(ct), StandardCharsets.UTF_8);
                        js("window.nativeBio&&window.nativeBio(true," + JSONObject.quote(pw) + ",'')");
                    } catch (Exception e) {
                        js("window.nativeBio&&window.nativeBio(false,'','Use your password.')");
                    }
                }

                @Override
                public void onAuthenticationError(int code, CharSequence err) {
                    boolean cancel = code == BiometricPrompt.ERROR_USER_CANCELED
                            || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                            || code == BiometricPrompt.ERROR_CANCELED;
                    js("window.nativeBio&&window.nativeBio(false,''," + (cancel ? "'cancel'" : JSONObject.quote(String.valueOf(err))) + ")");
                }
            });
            bp.authenticate(promptInfo("Unlock Habbit Strick"), new BiometricPrompt.CryptoObject(c));
        } catch (KeyPermanentlyInvalidatedException e) {
            clearBio();
            js("window.nativeBio&&window.nativeBio(false,'','Your fingerprints changed. Use your password.')");
        } catch (Exception e) {
            clearBio();
            js("window.nativeBio&&window.nativeBio(false,'','Fingerprint unlock stopped working. Use your password.')");
        }
    }

    private void clearBio() {
        prefs().edit().remove("bioCt").remove("bioIv").apply();
        deleteKey();
    }

    /* ---------- updater ---------- */

    private boolean downloading = false;

    private void startUpdate() {
        if (downloading) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            js("window.nativeUpdState&&window.nativeUpdState('needperm','Allow installs from Habbit Strick in the screen that just opened, then come back and tap Update again.',0)");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
            }
            return;
        }
        downloading = true;
        js("window.nativeUpdState&&window.nativeUpdState('downloading','',0)");
        new Thread(() -> {
            try {
                final java.io.File f = Updater.download(getApplicationContext(),
                        pct -> js("window.nativeUpdState&&window.nativeUpdState('progress',''," + pct + ")"));
                downloading = false;
                runOnUiThread(() -> {
                    try {
                        Uri u = androidx.core.content.FileProvider.getUriForFile(MainActivity.this,
                                getPackageName() + ".fileprovider", f);
                        Intent in = new Intent(Intent.ACTION_VIEW);
                        in.setDataAndType(u, "application/vnd.android.package-archive");
                        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(in);
                        js("window.nativeUpdState&&window.nativeUpdState('installing','Tap Install on the next screen. Your data stays safe.',100)");
                    } catch (Exception e) {
                        js("window.nativeUpdState&&window.nativeUpdState('error','Could not open the installer.',0)");
                    }
                });
            } catch (Exception e) {
                downloading = false;
                js("window.nativeUpdState&&window.nativeUpdState('error','Download failed. Check your internet and try again.',0)");
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void buzz(int ms) {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {
        }
    }

    /* ---------- bridge for the web page ---------- */

    private class Bridge {

        @JavascriptInterface
        public void haptic(int ms) {
            buzz(Math.max(5, Math.min(60, ms)));
        }

        @JavascriptInterface
        public String takeQueue() {
            SharedPreferences p = prefs();
            String q = p.getString("wq", "");
            if (q == null || q.isEmpty() || "[]".equals(q)) return "";
            p.edit().remove("wq").apply();
            return q;
        }

        @JavascriptInterface
        public String appVersion() {
            return Updater.installedName(getApplicationContext()) + " (" + Updater.installedCode(getApplicationContext()) + ")";
        }

        @JavascriptInterface
        public void checkUpdate(boolean manual) {
            final boolean m = manual;
            new Thread(() -> {
                String r = Updater.check(getApplicationContext(), m);
                js("window.nativeUpdate&&window.nativeUpdate(" + JSONObject.quote(r) + ")");
            }).start();
        }

        @JavascriptInterface
        public void downloadUpdate() {
            runOnUiThread(() -> startUpdate());
        }

        @JavascriptInterface
        public void sync(String json) {
            try {
                Reminders.applySync(getApplicationContext(), json);
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void askNotify() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, 77);
                }
            });
        }

        @JavascriptInterface
        public void saveBackup(String name, String text) {
            pendingBackup = text;
            runOnUiThread(() -> createDoc.launch(name));
        }

        @JavascriptInterface
        public void pickBackup() {
            runOnUiThread(() -> openDoc.launch(new String[]{"*/*"}));
        }

        @JavascriptInterface
        public boolean bioAvailable() {
            return bioAvailableNow();
        }

        @JavascriptInterface
        public boolean bioEnabled() {
            return prefs().contains("bioCt") && prefs().contains("bioIv");
        }

        @JavascriptInterface
        public void bioEnable(String pw) {
            runOnUiThread(() -> doBioEnable(pw));
        }

        @JavascriptInterface
        public void bioUnlock() {
            runOnUiThread(() -> doBioUnlock());
        }

        @JavascriptInterface
        public void bioDisable() {
            clearBio();
        }
    }
}
