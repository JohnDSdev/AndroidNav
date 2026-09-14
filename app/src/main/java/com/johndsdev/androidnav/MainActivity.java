package com.johndsdev.androidnav;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

public class MainActivity extends Activity {
    private static final int REQ_BACKGROUND=42;
    private static final String BACKGROUND_FILE="androidnav_background.jpg";
    private WebView webView;
    private volatile PlaybackService playback;
    private boolean bound;
    private interface PlayerAction { void accept(PlaybackService player); }
    private final List<PlayerAction> pending = new ArrayList<>();
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback=((PlaybackService.LocalBinder)binder).getService();
            for(PlayerAction action:pending)action.accept(playback);
            pending.clear();
        }
        @Override public void onServiceDisconnected(ComponentName name) { playback=null; }
    };
    private void withPlayer(PlayerAction action) {
        runOnUiThread(() -> { if(playback!=null)action.accept(playback); else pending.add(action); });
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        bound=bindService(new Intent(this,PlaybackService.class),connection,Context.BIND_AUTO_CREATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        webView.addJavascriptInterface(new PlayerBridge(), "AndroidPlayer");
        webView.setWebViewClient(new WebViewClient());

        webView.loadUrl("file:///android_asset/index.html");
    }

    private File backgroundFile() {
        return new File(getFilesDir(), BACKGROUND_FILE);
    }

    private boolean saveBackgroundFromUri(Uri uri) {
        if (uri == null) return false;
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream first = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(first, null, bounds);
            }

            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > 2200 && sample < 32) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            try (InputStream second = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(second, null, opts);
            }
            if (bitmap == null) return false;

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int max = Math.max(width, height);
            if (max > 1800) {
                float scale = 1800f / max;
                int newW = Math.max(1, Math.round(width * scale));
                int newH = Math.max(1, Math.round(height * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
                if (scaled != bitmap) bitmap.recycle();
                bitmap = scaled;
            }

            try (FileOutputStream out = new FileOutputStream(backgroundFile())) {
                return bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
        } catch (Exception ignored) {
            return false;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private String backgroundDataUrl() {
        File file = backgroundFile();
        if (!file.exists()) return "";
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) {
            return "";
        }
    }

    private void notifyBackgroundChanged() {
        withPlayer(p -> p.command("artwork",0));
        runJs("window.androidBackgroundChanged && window.androidBackgroundChanged()");
    }

    private void runJs(String script) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    public final class PlayerBridge {
        @JavascriptInterface public void playQueue(String json,int index) { withPlayer(p -> p.playQueue(json,index)); }
        @JavascriptInterface public void toggle() { withPlayer(p -> p.command("toggle",0)); }
        @JavascriptInterface public void pause() { withPlayer(p -> p.command("pause",0)); }
        @JavascriptInterface public void seekTo(int ms) { withPlayer(p -> p.command("seek",ms)); }
        @JavascriptInterface public void next() { withPlayer(p -> p.command("next",0)); }
        @JavascriptInterface public void previous() { withPlayer(p -> p.command("previous",0)); }
        @JavascriptInterface public void cycleRepeatMode() { withPlayer(p -> p.command("repeat",0)); }
        @JavascriptInterface public void stop() { withPlayer(p -> p.command("stop",0)); }
        @JavascriptInterface public String getState() { PlaybackService p=playback; return p==null?"{}":p.getState(); }
        @JavascriptInterface
        public void chooseBackground() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(intent, REQ_BACKGROUND);
            });
        }

        @JavascriptInterface
        public void clearBackground() {
            runOnUiThread(() -> {
                File file = backgroundFile();
                if (file.exists()) file.delete();
                notifyBackgroundChanged();
            });
        }

        @JavascriptInterface
        public String getBackgroundDataUrl() {
            return backgroundDataUrl();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BACKGROUND && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            boolean saved = saveBackgroundFromUri(uri);
            if (saved) notifyBackgroundChanged();
            else runJs("window.androidBackgroundError && window.androidBackgroundError()");
        }
    }

    @Override protected void onDestroy() {
        pending.clear();
        if(bound) { unbindService(connection); bound=false; }
        playback=null;
        if(webView!=null) { webView.removeJavascriptInterface("AndroidPlayer"); webView.destroy(); webView=null; }
        super.onDestroy();
    }
}
