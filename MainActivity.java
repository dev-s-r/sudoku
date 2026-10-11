package com.sudoku.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import com.getcapacitor.BridgeActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {

  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(NativeCam.class);
    otaBoot();
    super.onCreate(savedInstanceState);
    otaApply();
    getBridge().getWebView().addJavascriptInterface(new NativeBridge(), "AndroidBridge");
  }

  /* ---------- quick web updates ---------- */
  private static final String OTA_PREFS = "sudoku_ota";
  private static final String CAP_PREFS = "CapWebViewSettings";
  private static final String CAP_PATH = "serverBasePath";

  private File otaRoot() { return new File(getFilesDir(), "ota"); }

  private void otaClear() {
    getSharedPreferences(CAP_PREFS, MODE_PRIVATE).edit().remove(CAP_PATH).commit();
    getSharedPreferences(OTA_PREFS, MODE_PRIVATE).edit().putBoolean("pending", false).remove("name").commit();
    OtaUpdater.prune(otaRoot(), "\u0000");
  }

  // Runs before the web view starts: a new app version, or an update that never reported success, goes back to the built-in files.
  private void otaBoot() {
    try {
      SharedPreferences p = getSharedPreferences(OTA_PREFS, MODE_PRIVATE);
      PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
      long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
      if (p.getLong("apk", -1) != code) {
        otaClear();
        p.edit().putLong("apk", code).commit();
      } else if (p.getBoolean("pending", false)) {
        otaClear();
      }
    } catch (Exception e) {
      otaClear();
    }
  }

  // After the web view exists: make sure the saved update folder is the one being shown.
  private void otaApply() {
    try {
      String name = getSharedPreferences(OTA_PREFS, MODE_PRIVATE).getString("name", null);
      if (name == null) return;
      File dir = new File(otaRoot(), name);
      if (!new File(dir, "index.html").isFile()) { otaClear(); return; }
      String now = null;
      try { now = (String) getBridge().getClass().getMethod("getServerBasePath").invoke(getBridge()); } catch (Exception ignored) { /* reload below */ }
      if (now == null || !now.equals(dir.getAbsolutePath())) getBridge().setServerBasePath(dir.getAbsolutePath());
    } catch (Exception e) { /* keep the built-in files */ }
  }

  /* ---------- in-app updates ---------- */
  private static final String INSTALL_ACTION = "com.sudoku.app.INSTALL_RESULT";
  private BroadcastReceiver installReceiver;

  private void updJs(String call) {
    runOnUiThread(() -> getBridge().getWebView().evaluateJavascript(
      "window." + call.split("\\(")[0] + " && window." + call, null));
  }

  private void updResult(String status, String msg) {
    updJs("__updResult(" + JSONObject.quote(status) + "," + JSONObject.quote(msg == null ? "" : msg) + ")");
  }

  private boolean sameKey(File apk) {
    try {
      if (Build.VERSION.SDK_INT < 28) return true;
      PackageManager pm = getPackageManager();
      PackageInfo mine = pm.getPackageInfo(getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
      PackageInfo theirs = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
      if (theirs == null || theirs.signingInfo == null || mine.signingInfo == null) return true;
      Signature[] a = mine.signingInfo.getApkContentsSigners();
      Signature[] b = theirs.signingInfo.getApkContentsSigners();
      if (a == null || b == null || a.length == 0 || b.length == 0) return true;
      return a[0].equals(b[0]);
    } catch (Exception e) {
      return true;
    }
  }

  private void registerInstallReceiver() {
    if (installReceiver != null) return;
    installReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
          Intent conf = i.getParcelableExtra(Intent.EXTRA_INTENT);
          if (conf != null) {
            conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(conf);
          }
          updResult("confirm", "");
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
          updResult("success", "");
        } else {
          String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
          updResult("error", m == null ? "The update could not be installed." : m);
        }
      }
    };
    IntentFilter f = new IntentFilter(INSTALL_ACTION);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(installReceiver, f);
  }

  private void downloadAndInstall(String urlStr) {
    try {
      File dir = new File(getCacheDir(), "update");
      dir.mkdirs();
      File apk = new File(dir, "Sudoku.apk");
      java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
      c.setConnectTimeout(20000);
      c.setReadTimeout(30000);
      c.setInstanceFollowRedirects(true);
      int code = c.getResponseCode();
      if (code / 100 != 2) throw new Exception("Download failed (" + code + ")");
      long total = c.getContentLengthLong();
      try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
        byte[] buf = new byte[65536];
        long done = 0;
        int n, last = -1;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
          done += n;
          if (total > 0) {
            int p = (int) (done * 100 / total);
            if (p != last) { last = p; updJs("__updProgress(" + p + ")"); }
          }
        }
      }
      if (!sameKey(apk)) {
        updResult("error", "This update is signed with a different key, so Android will not install it over this app. It needs one reinstall. Save a backup first.");
        return;
      }
      PackageInstaller pi = getPackageManager().getPackageInstaller();
      PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
      p.setAppPackageName(getPackageName());
      if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
      int id = pi.createSession(p);
      registerInstallReceiver();
      try (PackageInstaller.Session s = pi.openSession(id)) {
        try (InputStream in = new java.io.FileInputStream(apk); OutputStream out = s.openWrite("Sudoku.apk", 0, apk.length())) {
          byte[] buf = new byte[65536];
          int n;
          while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
          s.fsync(out);
        }
        Intent cb = new Intent(INSTALL_ACTION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pend = PendingIntent.getBroadcast(this, id, cb, flags);
        s.commit(pend.getIntentSender());
      }
    } catch (Exception e) {
      updResult("error", e.getMessage() == null ? "The update failed." : e.getMessage());
    }
  }

  /* ---------- what the web page can call as AndroidBridge ---------- */
  private class NativeBridge {
    @JavascriptInterface
    public void otaInstall(final String url, final String name) {
      new Thread(() -> {
        try {
          final File dir = OtaUpdater.downloadAndExtract(url, otaRoot(), "b" + name.replaceAll("[^0-9A-Za-z]", ""),
            p -> updJs("__updProgress(" + p + ")"));
          getSharedPreferences(OTA_PREFS, MODE_PRIVATE).edit().putString("name", dir.getName()).putBoolean("pending", true).commit();
          getSharedPreferences(CAP_PREFS, MODE_PRIVATE).edit().putString(CAP_PATH, dir.getAbsolutePath()).commit();
          runOnUiThread(() -> getBridge().setServerBasePath(dir.getAbsolutePath()));
        } catch (Exception e) {
          updResult("error", e.getMessage() == null ? "The update could not be downloaded." : e.getMessage());
        }
      }).start();
    }

    @JavascriptInterface
    public void otaReady() {
      SharedPreferences sp = getSharedPreferences(OTA_PREFS, MODE_PRIVATE);
      if (!sp.getBoolean("pending", false)) return;
      sp.edit().putBoolean("pending", false).commit();
      String keep = sp.getString("name", null);
      if (keep != null) OtaUpdater.prune(otaRoot(), keep);
    }

    @JavascriptInterface
    public String apkInfo() {
      try {
        PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
        long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        boolean can = Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        return new JSONObject().put("versionCode", code).put("versionName", pi.versionName).put("canInstall", can).toString();
      } catch (Exception e) {
        return "{}";
      }
    }

    @JavascriptInterface
    public void openInstallPermission() {
      try {
        Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
      } catch (Exception e) { /* ignore */ }
    }

    @JavascriptInterface
    public void installApk(final String url) {
      new Thread(() -> downloadAndInstall(url)).start();
    }
  }
}
