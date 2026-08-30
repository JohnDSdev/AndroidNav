package com.johndsdev.androidnav;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO_PERMISSION = 40;
    private static final int REQ_FILE_CHOOSER = 41;

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private MusicBridge musicBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        musicBridge = new MusicBridge(this);
        webView.addJavascriptInterface(musicBridge, "AndroidMusic");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] accepted = fileChooserParams.getAcceptTypes();
                if (accepted != null && accepted.length > 0) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, accepted);
                }
                startActivityForResult(intent, REQ_FILE_CHOOSER);
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER && fileChooserCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION) {
            webView.evaluateJavascript("window.AndroidNav && window.AndroidNav.refreshSongs();", null);
        }
    }

    @Override
    protected void onDestroy() {
        if (musicBridge != null) {
            musicBridge.release();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    public final class MusicBridge {
        private final Activity activity;
        private MediaPlayer player;
        private String currentUri = "";

        MusicBridge(Activity activity) {
            this.activity = activity;
        }

        private String audioPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return Manifest.permission.READ_MEDIA_AUDIO;
            }
            return Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        @JavascriptInterface
        public boolean hasAudioPermission() {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || activity.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestAudioPermission() {
            activity.runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasAudioPermission()) {
                    activity.requestPermissions(new String[]{audioPermission()}, REQ_AUDIO_PERMISSION);
                } else {
                    webView.evaluateJavascript("window.AndroidNav && window.AndroidNav.refreshSongs();", null);
                }
            });
        }

        @JavascriptInterface
        public String getSongs() {
            JSONArray songs = new JSONArray();
            if (!hasAudioPermission()) {
                return songs.toString();
            }

            Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION
            };
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

            try (Cursor cursor = getContentResolver().query(collection, projection, selection, null, sort)) {
                if (cursor == null) return songs.toString();

                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    JSONObject song = new JSONObject();
                    song.put("id", id);
                    song.put("title", safe(cursor.getString(titleCol), "Untitled"));
                    song.put("artist", safe(cursor.getString(artistCol), "Unknown artist"));
                    song.put("album", safe(cursor.getString(albumCol), ""));
                    song.put("duration", cursor.getLong(durationCol));
                    song.put("uri", Uri.withAppendedPath(collection, Long.toString(id)).toString());
                    songs.put(song);
                }
            } catch (Exception ignored) {
                return "[]";
            }
            return songs.toString();
        }

        private String safe(String value, String fallback) {
            if (value == null || value.trim().isEmpty() || "<unknown>".equalsIgnoreCase(value)) {
                return fallback;
            }
            return value;
        }

        @JavascriptInterface
        public void play(String uri) {
            if (uri == null || uri.isEmpty()) return;
            activity.runOnUiThread(() -> {
                releasePlayer();
                try {
                    currentUri = uri;
                    MediaPlayer next = new MediaPlayer();
                    player = next;
                    next.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    next.setDataSource(activity, Uri.parse(uri));
                    next.setOnPreparedListener(mp -> {
                        mp.start();
                        sendPlaybackState("playing");
                    });
                    next.setOnCompletionListener(mp -> sendPlaybackState("ended"));
                    next.setOnErrorListener((mp, what, extra) -> {
                        sendPlaybackState("error");
                        return true;
                    });
                    next.prepareAsync();
                    sendPlaybackState("loading");
                } catch (Exception e) {
                    releasePlayer();
                    sendPlaybackState("error");
                }
            });
        }

        @JavascriptInterface
        public void pause() {
            activity.runOnUiThread(() -> {
                if (player != null && player.isPlaying()) {
                    player.pause();
                    sendPlaybackState("paused");
                }
            });
        }

        @JavascriptInterface
        public void resume() {
            activity.runOnUiThread(() -> {
                if (player != null) {
                    try {
                        player.start();
                        sendPlaybackState("playing");
                    } catch (IllegalStateException ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            activity.runOnUiThread(() -> {
                releasePlayer();
                currentUri = "";
                sendPlaybackState("stopped");
            });
        }

        @JavascriptInterface
        public boolean isPlaying() {
            try {
                return player != null && player.isPlaying();
            } catch (IllegalStateException e) {
                return false;
            }
        }

        private void sendPlaybackState(String state) {
            String js = "window.AndroidNav && window.AndroidNav.onPlaybackState('" + state + "');";
            webView.evaluateJavascript(js, null);
        }

        private void releasePlayer() {
            if (player != null) {
                try {
                    player.reset();
                } catch (Exception ignored) {
                }
                player.release();
                player = null;
            }
        }

        void release() {
            activity.runOnUiThread(this::releasePlayer);
        }
    }
}
