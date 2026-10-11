package com.sudoku.app;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads a web bundle (zip) and unpacks it into a folder. Plain Java, no Android classes. */
public final class OtaUpdater {
  public interface Progress { void onProgress(int percent); }

  private OtaUpdater() {}

  /** Downloads urlStr, unpacks to root/name and returns that folder (it contains index.html). */
  public static File downloadAndExtract(String urlStr, File root, String name, Progress progress) throws IOException {
    if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create the update folder");
    File zip = new File(root, name + ".zip.tmp");
    File part = new File(root, name + ".part");
    File dest = new File(root, name);
    deleteTree(zip); deleteTree(part); deleteTree(dest);
    try {
      download(urlStr, zip, progress);
      unzip(zip, part);
      File base = part;
      if (!new File(base, "index.html").isFile()) {
        File[] kids = part.listFiles();
        if (kids != null && kids.length == 1 && kids[0].isDirectory() && new File(kids[0], "index.html").isFile()) base = kids[0];
        else throw new IOException("The update has no index.html");
      }
      if (!base.renameTo(dest)) throw new IOException("Could not save the update");
      return dest;
    } catch (IOException | RuntimeException e) {
      deleteTree(part); deleteTree(dest);
      throw e;
    } finally {
      deleteTree(zip); deleteTree(part);
    }
  }

  /** Deletes every folder in root except keepName. */
  public static void prune(File root, String keepName) {
    File[] all = root.listFiles();
    if (all == null) return;
    for (File f : all) if (!f.getName().equals(keepName)) deleteTree(f);
  }

  static void download(String urlStr, File out, Progress progress) throws IOException {
    String cur = urlStr;
    HttpURLConnection c = null;
    for (int hop = 0; hop < 8; hop++) {
      c = (HttpURLConnection) new URL(cur).openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(20000);
      c.setReadTimeout(60000);
      c.setRequestProperty("User-Agent", "Sudoku-Updater");
      c.setRequestProperty("Accept-Encoding", "identity");
      int code = c.getResponseCode();
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        String loc = c.getHeaderField("Location");
        c.disconnect();
        if (loc == null) throw new IOException("Bad redirect");
        cur = new URL(new URL(cur), loc).toString();
        if (!cur.startsWith("https://") && !cur.startsWith("http://localhost") && !cur.startsWith("http://127.0.0.1")) throw new IOException("Blocked a redirect to an unsafe address");
        c = null;
        continue;
      }
      if (code / 100 != 2) { c.disconnect(); throw new IOException("Download failed (HTTP " + code + ")"); }
      break;
    }
    if (c == null) throw new IOException("Too many redirects");
    long total = c.getContentLengthLong();
    try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
      byte[] buf = new byte[65536];
      long done = 0; int n, last = -1;
      while ((n = in.read(buf)) > 0) {
        os.write(buf, 0, n);
        done += n;
        if (progress != null && total > 0) {
          int p = (int) (done * 100 / total);
          if (p != last) { last = p; progress.onProgress(p); }
        }
      }
      if (total > 0 && done != total) throw new IOException("The download was cut short");
    } finally {
      c.disconnect();
    }
    if (out.length() < 22) throw new IOException("The download is empty");
  }

  static void unzip(File zip, File into) throws IOException {
    if (!into.mkdirs() && !into.isDirectory()) throw new IOException("Could not create a folder");
    String base = into.getCanonicalPath() + File.separator;
    try (ZipInputStream zin = new ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
      ZipEntry e;
      byte[] buf = new byte[65536];
      long total = 0;
      while ((e = zin.getNextEntry()) != null) {
        String nm = e.getName().replace('\\', '/');
        if (nm.startsWith("./")) nm = nm.substring(2);
        if (nm.isEmpty()) continue;
        File f = new File(into, nm);
        String canon = f.getCanonicalPath();
        if (!(canon + (e.isDirectory() ? File.separator : "")).startsWith(base)) throw new IOException("Unsafe file name in the update");
        if (e.isDirectory()) { f.mkdirs(); continue; }
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create a folder");
        try (OutputStream os = new FileOutputStream(f)) {
          int n;
          while ((n = zin.read(buf)) > 0) {
            os.write(buf, 0, n);
            total += n;
            if (total > 400L * 1024 * 1024) throw new IOException("The update is too large");
          }
        }
      }
    }
  }

  static void deleteTree(File f) {
    if (f == null || !f.exists()) return;
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) deleteTree(k);
    f.delete();
  }
}
