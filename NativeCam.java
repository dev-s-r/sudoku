package com.sudoku.app;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

@CapacitorPlugin(
    name = "NativeCam",
    permissions = {
        @Permission(alias = "media", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = "media33", strings = { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO })
    }
)
public class NativeCam extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "media33" : "media";
    }

    @PluginMethod
    public void ensure(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) {
            call.resolve();
            return;
        }
        requestPermissionForAlias(alias(), call, "permDone");
    }

    @PermissionCallback
    private void permDone(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) {
            call.resolve();
        } else {
            call.reject("Allow access to photos and videos (choose Allow all) so the app can import what you capture.");
        }
    }

    @PluginMethod
    public void open(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            call.reject("Photo and video access is not allowed.");
            return;
        }
        launch(call);
    }

    private void launch(PluginCall call) {
        try {
            Intent i = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not open the camera app.");
        }
    }

    @PluginMethod
    public void list(PluginCall call) {
        long since = call.getLong("since", 0L);
        JSArray arr = new JSArray();
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "img", since, arr);
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "vid", since, arr);
        JSObject o = new JSObject();
        o.put("files", arr);
        call.resolve(o);
    }

    private void query(Uri base, String kind, long since, JSArray out) {
        String[] proj = new String[] {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE
        };
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(
                base, proj,
                MediaStore.MediaColumns.DATE_ADDED + ">=?",
                new String[] { String.valueOf(since) },
                MediaStore.MediaColumns.DATE_ADDED + " ASC");
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                JSObject f = new JSObject();
                f.put("uri", ContentUris.withAppendedId(base, id).toString());
                f.put("name", c.getString(1));
                f.put("mime", c.getString(2));
                f.put("size", c.getLong(3));
                f.put("kind", kind);
                out.put(f);
            }
        } catch (Exception e) {
            // ignore: returns what was found so far
        } finally {
            if (c != null) c.close();
        }
    }

    @PluginMethod
    public void copy(PluginCall call) {
        String u = call.getString("uri");
        String name = call.getString("name", "file");
        if (u == null) {
            call.reject("No file");
            return;
        }
        try {
            File dir = new File(getContext().getCacheDir(), "vcam");
            dir.mkdirs();
            File out = new File(dir, System.nanoTime() + "_" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
            InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(u));
            if (in == null) {
                call.reject("Could not open file");
                return;
            }
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) {
                    os.write(b, 0, n);
                }
            } finally {
                os.close();
                in.close();
            }
            JSObject o = new JSObject();
            o.put("path", out.getAbsolutePath());
            call.resolve(o);
        } catch (Exception e) {
            call.reject("Could not read file: " + e.getMessage());
        }
    }

    @PluginMethod
    public void clean(PluginCall call) {
        try {
            File dir = new File(getContext().getCacheDir(), "vcam");
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    f.delete();
                }
            }
        } catch (Exception e) {
            // ignore
        }
        call.resolve();
    }
}
