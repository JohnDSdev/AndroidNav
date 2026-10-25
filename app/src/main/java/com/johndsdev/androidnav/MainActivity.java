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
import android.database.Cursor;
import android.provider.OpenableColumns;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_BACKGROUND = 42;
    private static final int REQ_FONT = 43;
    private static final String FONT_FILE = "androidnav_user_font.bin";
    private static final String BACKGROUND_FILE = "androidnav_background.jpg";
    private static final String CHANNEL_ID = "androidnav_playback";
    private static final int NOTIFICATION_ID = 4107;

    private static final String ACTION_PREVIOUS = "com.johndsdev.androidnav.PREVIOUS";
    private static final String ACTION_TOGGLE = "com.johndsdev.androidnav.TOGGLE";
    private static final String ACTION_NEXT = "com.johndsdev.androidnav.NEXT";
    private static final String ACTION_REPEAT = "com.johndsdev.androidnav.REPEAT";
    private static final String ACTION_STOP = "com.johndsdev.androidnav.STOP";
    private static final String CUSTOM_REPEAT = "com.johndsdev.androidnav.REPEAT_CUSTOM";

    private WebView webView;
    private MediaPlayer mediaPlayer;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private NotificationManager notificationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService telemetryExecutor = Executors.newSingleThreadExecutor();
    // Each playback of a track is counted independently, including repeat-one.
    private String telemetryStreamUrl = "";
    private long telemetryStartedAt = 0;
    private long telemetryLastTickAt = 0;
    private long telemetryListenedMs = 0;
    private boolean telemetrySubmitted = false;
    private long telemetryLastNowPlayingMs = 0;
    private volatile String telemetryNowPlayingStatus = "not sent yet";
    private volatile String telemetryPlayStatus = "not submitted yet";
    private volatile String telemetryStatusTrack = "";
    private volatile long telemetryLastNowPlayingSuccessAt = 0;
    private volatile long telemetryLastPlaySuccessAt = 0;

    private final List<QueueTrack> queue = new ArrayList<>();
    private volatile int currentQueueIndex = -1;
    private volatile int repeatMode = 0;

    private volatile String currentId = "";
    private volatile String currentTitle = "";
    private volatile String currentArtist = "";
    private volatile String currentAlbum = "";
    private volatile int cachedPositionMs = 0;
    private volatile int cachedDurationMs = 0;
    private volatile boolean isPlaying = false;
    private volatile boolean prepared = false;

    private static final class QueueTrack {
        final String url;
        final String id;
        final String title;
        final String artist;
        final String album;
        final int durationSeconds;

        QueueTrack(String url, String id, String title, String artist, String album, int durationSeconds) {
            this.url = url == null ? "" : url;
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.album = album == null ? "" : album;
            this.durationSeconds = Math.max(0, durationSeconds);
        }
    }

    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        runOnUiThread(() -> {
            if (mediaPlayer == null) return;
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pauseInternal();
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                try { mediaPlayer.setVolume(0.25f, 0.25f); } catch (Exception ignored) {}
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                try { mediaPlayer.setVolume(1f, 1f); } catch (Exception ignored) {}
            }
        });
    };

    private final BroadcastReceiver playbackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            switch (intent.getAction()) {
                case ACTION_PREVIOUS: previousInternal(); break;
                case ACTION_TOGGLE: toggleInternal(); break;
                case ACTION_NEXT: nextInternal(false); break;
                case ACTION_REPEAT: cycleRepeatModeInternal(); break;
                case ACTION_STOP: stopInternal(true); break;
                default: break;
            }
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && prepared) {
                try {
                    cachedPositionMs = mediaPlayer.getCurrentPosition();
                    cachedDurationMs = Math.max(cachedDurationMs, mediaPlayer.getDuration());
                    recordPlaybackProgress();
                } catch (IllegalStateException ignored) {}
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        createMediaSession();
        registerPlaybackReceiver();

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

        handler.post(progressTicker);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "AndroidNav");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resumeInternal(); }
            @Override public void onPause() { pauseInternal(); }
            @Override public void onStop() { stopInternal(true); }
            @Override public void onSeekTo(long pos) { seekInternal((int) Math.max(0, Math.min(Integer.MAX_VALUE, pos))); }
            @Override public void onSkipToNext() { nextInternal(false); }
            @Override public void onSkipToPrevious() { previousInternal(); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if (CUSTOM_REPEAT.equals(action)) cycleRepeatModeInternal();
            }
        });
        mediaSession.setActive(true);
        updatePlaybackState(PlaybackState.STATE_NONE);
    }

    private void registerPlaybackReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PREVIOUS);
        filter.addAction(ACTION_TOGGLE);
        filter.addAction(ACTION_NEXT);
        filter.addAction(ACTION_REPEAT);
        filter.addAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(playbackReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(playbackReceiver, filter);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("AndroidNav playback controls");
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private File fontFile() { return new File(getFilesDir(), FONT_FILE); }

    private String fontFileName() {
        if (!fontFile().isFile()) return "";
        return getPreferences(MODE_PRIVATE).getString("selected_font_name", "custom font");
    }

    // Accept only actual font data, not arbitrary files with a .ttf extension.
    private String fontType(byte[] header) {
        if (header.length < 4) return "";
        String magic = new String(header, 0, 4, StandardCharsets.ISO_8859_1);
        if ("OTTO".equals(magic)) return "font/otf";
        if ("wOFF".equals(magic)) return "font/woff";
        if ("wOF2".equals(magic)) return "font/woff2";
        if ((header[0] == 0 && header[1] == 1 && header[2] == 0 && header[3] == 0) || "true".equals(magic)) return "font/ttf";
        return "";
    }

    private boolean saveFontFromUri(Uri uri) {
        if (uri == null) return false;
        File temp = new File(getFilesDir(), "androidnav_font_pending.tmp");
        String displayName = "custom font";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) displayName = cursor.getString(0);
        } catch (Exception ignored) {}
        if (displayName == null || displayName.trim().isEmpty()) displayName = "custom font";
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int n;
            long count = 0;
            while ((n = in.read(buf)) != -1) {
                count += n;
                if (count > 8 * 1024 * 1024) { temp.delete(); return false; }
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) { temp.delete(); return false; }
        try (FileInputStream in = new FileInputStream(temp)) {
            byte[] header = new byte[4];
            if (in.read(header) != 4 || fontType(header).isEmpty()) return false;
            if (!temp.renameTo(fontFile())) return false;
            getPreferences(MODE_PRIVATE).edit().putString("selected_font_name", displayName).apply();
            return true;
        } catch (Exception ignored) { return false; }
        finally { if (temp.exists()) temp.delete(); }
    }

    private String fontDataUrl() {
        if (!fontFile().isFile()) return "";
        try (FileInputStream in = new FileInputStream(fontFile()); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            byte[] data = out.toByteArray();
            String type = fontType(data);
            if (type.isEmpty()) return "";
            return "data:" + type + ";base64," + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Exception ignored) { return ""; }
    }

    private void notifyFontChanged() {
        runJs("window.androidFontChanged && window.androidFontChanged()");
    }

    private File backgroundFile() {
        return new File(getFilesDir(), BACKGROUND_FILE);
    }

    private Bitmap loadBackgroundBitmap(int maxDimension) {
        File file = backgroundFile();
        if (!file.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / sample > maxDimension && sample < 16) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception ignored) {
            return null;
        }
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
        updateMetadata();
        updateNotification();
        runJs("window.androidBackgroundChanged && window.androidBackgroundChanged()");
    }

    private void runJs(String script) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private void notifyWebPlayerChanged() {
        runJs("window.androidPlaybackStateChanged && window.androidPlaybackStateChanged()");
    }

    private String repeatLabel() {
        if (repeatMode == 2) return "Repeat one";
        if (repeatMode == 1) return "Repeat all";
        return "Repeat off";
    }

    private void updateSessionQueue() {
        if (mediaSession == null) return;
        List<MediaSession.QueueItem> items = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            QueueTrack t = queue.get(i);
            MediaDescription description = new MediaDescription.Builder()
                    .setMediaId(t.id)
                    .setTitle(t.title)
                    .setSubtitle(t.artist)
                    .build();
            items.add(new MediaSession.QueueItem(description, i));
        }
        mediaSession.setQueue(items);
        mediaSession.setQueueTitle("AndroidNav queue");
    }

    private void updateMetadata() {
        if (mediaSession == null) return;
        // Some Android media surfaces concatenate artist and album. Avoid repeating identical metadata.
        String distinctAlbum = currentAlbum.trim().equalsIgnoreCase(currentArtist.trim()) ? "" : currentAlbum;
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, currentId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, distinctAlbum)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, currentArtist)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, distinctAlbum)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, cachedDurationMs);
        if (currentQueueIndex >= 0) builder.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, currentQueueIndex + 1L);
        Bitmap art = loadBackgroundBitmap(1024);
        if (art != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                    .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art);
        }
        mediaSession.setMetadata(builder.build());
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT
                | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP;
        PlaybackState.CustomAction repeatAction = new PlaybackState.CustomAction.Builder(
                CUSTOM_REPEAT, repeatLabel(), repeatMode == 2 ? R.drawable.ic_repeat_one_modern : R.drawable.ic_repeat_modern).build();
        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(actions)
                .addCustomAction(repeatAction)
                .setState(state, cachedPositionMs, state == PlaybackState.STATE_PLAYING ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build();
        mediaSession.setPlaybackState(playbackState);
    }

    private PendingIntent broadcastPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(action).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(this, requestCode, intent, flags);
    }

    private void updateNotification() {
        if (currentId.isEmpty() || notificationManager == null || mediaSession == null) return;

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, openIntent, pendingFlags);

        Notification.Action previousAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous", broadcastPendingIntent(ACTION_PREVIOUS, 2)).build();
        Notification.Action toggleAction = new Notification.Action.Builder(
                isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying ? "Pause" : "Play", broadcastPendingIntent(ACTION_TOGGLE, 3)).build();
        Notification.Action nextAction = new Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Next", broadcastPendingIntent(ACTION_NEXT, 4)).build();
        Notification.Action repeatAction = new Notification.Action.Builder(
                repeatMode == 2 ? R.drawable.ic_repeat_one_modern : R.drawable.ic_repeat_modern,
                repeatLabel(), broadcastPendingIntent(ACTION_REPEAT, 5)).build();

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Bitmap art = loadBackgroundBitmap(768);
        builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle.isEmpty() ? "AndroidNav" : currentTitle)
                .setContentText(currentArtist)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(isPlaying)
                .addAction(previousAction)
                .addAction(toggleAction)
                .addAction(nextAction)
                .addAction(repeatAction)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) builder.setLargeIcon(art);
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void requestAudioFocus() {
        if (audioManager != null) {
            audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager != null) audioManager.abandonAudioFocus(audioFocusListener);
    }

    private void resetTelemetrySession() {
        telemetryStreamUrl = "";
        telemetryStartedAt = 0;
        telemetryLastTickAt = 0;
        telemetryListenedMs = 0;
        telemetrySubmitted = false;
    }

    // Subsonic 1.16: submission=false announces the active song; true counts a play.
    // Authenticate with the same credentials used to stream, and explicitly include song id.
    private void reportTelemetry(boolean submission) {
        final String source = telemetryStreamUrl;
        final String songId = currentId;
        final long startedAt = telemetryStartedAt;
        if (source.isEmpty() || songId.isEmpty()) {
            if (submission) telemetryPlayStatus = "missing song or server connection";
            else telemetryNowPlayingStatus = "missing song or server connection";
            return;
        }
        if (submission) telemetryPlayStatus = "sending…";
        else telemetryNowPlayingStatus = "sending…";
        telemetryExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                if (!submission && !songId.equals(currentId)) return;
                Uri stream = Uri.parse(source);
                String originalPath = stream.getEncodedPath();
                if (originalPath == null || !originalPath.endsWith("/stream.view")) throw new Exception("unexpected stream URL path");
                String scheme = stream.getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) throw new Exception("invalid server URL");
                String replacementPath = originalPath.substring(0, originalPath.length() - "stream.view".length()) + "scrobble.view";
                Uri.Builder request = stream.buildUpon().encodedPath(replacementPath).clearQuery();
                // Do not forward 'stream' options or trust that the original id query survived a URL rewrite.
                for (String key : new String[]{"u", "p", "t", "s", "v", "c"}) {
                    String value = stream.getQueryParameter(key);
                    if (value != null) request.appendQueryParameter(key, value);
                }
                request.appendQueryParameter("f", "json")
                        .appendQueryParameter("id", songId)
                        .appendQueryParameter("submission", String.valueOf(submission))
                        .appendQueryParameter("time", String.valueOf(startedAt));
                Uri endpoint = request.build();
                connection = (HttpURLConnection) new URL(endpoint.toString()).openConnection();
                connection.setConnectTimeout(9000);
                connection.setReadTimeout(9000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestMethod("GET");
                int http = connection.getResponseCode();
                if (http < 200 || http >= 300) throw new Exception("HTTP " + http);
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[4096];
                    int n;
                    while ((n = in.read(bytes)) != -1 && body.size() < 32768) body.write(bytes, 0, n);
                    JSONObject response = new JSONObject(body.toString("UTF-8")).getJSONObject("subsonic-response");
                    if (!"ok".equalsIgnoreCase(response.optString("status"))) {
                        JSONObject error = response.optJSONObject("error");
                        String code = error == null ? "unknown" : String.valueOf(error.optInt("code", -1));
                        // Deliberately omit the error message: a reverse proxy could echo credentials.
                        throw new Exception("Navidrome API error " + code);
                    }
                }
                if (songId.equals(currentId)) {
                    if (submission) {
                        telemetryPlayStatus = "accepted by server";
                        telemetryLastPlaySuccessAt = System.currentTimeMillis();
                    } else {
                        telemetryNowPlayingStatus = "accepted by server";
                        telemetryLastNowPlayingSuccessAt = System.currentTimeMillis();
                    }
                } else if (submission) {
                    telemetryLastPlaySuccessAt = System.currentTimeMillis();
                }
            } catch (Exception ex) {
                // Don't include the full URL, auth token or user password in either UI or logs.
                String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                if (message.length() > 100) message = ex.getClass().getSimpleName();
                if (songId.equals(currentId)) {
                    if (submission) telemetryPlayStatus = "failed: " + message;
                    else telemetryNowPlayingStatus = "failed: " + message;
                }
                android.util.Log.w("AndroidNav", "Telemetry " + (submission ? "play" : "now-playing") + " failed (" + ex.getClass().getSimpleName() + ")");
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void resetTelemetrySession() {
        telemetryStreamUrl = "";
        telemetryStartedAt = 0;
        telemetryLastTickAt = 0;
        telemetryLastNowPlayingMs = 0;
        telemetryListenedMs = 0;
        telemetrySubmitted = false;
    }

    private void beginTelemetry(String url) {
        resetTelemetrySession();
        telemetryStreamUrl = url == null ? "" : url;
        telemetryStartedAt = System.currentTimeMillis();
        telemetryLastTickAt = SystemClock.elapsedRealtime();
        telemetryLastNowPlayingMs = telemetryLastTickAt;
        telemetryStatusTrack = currentTitle;
        telemetryNowPlayingStatus = "queued";
        telemetryPlayStatus = "waiting for listening threshold";
        reportTelemetry(false);
    }

    private void recordPlaybackProgress() {
        if (!isPlaying || telemetryStartedAt == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (telemetryLastTickAt > 0)
            telemetryListenedMs += Math.min(5000, Math.max(0, now - telemetryLastTickAt));
        telemetryLastTickAt = now;

        // Navidrome's Now Playing sessions expire, so refresh them as playback continues.
        if (now - telemetryLastNowPlayingMs >= 30000) {
            telemetryLastNowPlayingMs = now;
            reportTelemetry(false);
        }

        if (!telemetrySubmitted) {
            long threshold = cachedDurationMs > 0 ? Math.max(30000, Math.min(240000, cachedDurationMs / 2L)) : 240000;
            if (telemetryListenedMs >= threshold) {
                telemetrySubmitted = true;
                reportTelemetry(true);
            }
        }
    }

    private String telemetryStatusJson() {
        try {
            JSONObject status = new JSONObject();
            status.put("track", telemetryStatusTrack);
            status.put("nowPlaying", telemetryNowPlayingStatus);
            status.put("playCount", telemetryPlayStatus);
            status.put("listenedSeconds", telemetryListenedMs / 1000L);
            status.put("lastNowPlayingSuccessAt", telemetryLastNowPlayingSuccessAt);
            status.put("lastPlaySuccessAt", telemetryLastPlaySuccessAt);
            return status.toString();
        } catch (Exception ignored) { return "{}"; }
    }

    private void parseQueue(String json) throws Exception {
        JSONArray array = new JSONArray(json == null ? "[]" : json);
        queue.clear();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            queue.add(new QueueTrack(
                    o.optString("url", ""),
                    o.optString("id", ""),
                    o.optString("title", "Untitled"),
                    o.optString("artist", "Unknown artist"),
                    o.optString("album", ""),
                    o.optInt("durationSeconds", 0)
            ));
        }
        updateSessionQueue();
    }

    private void playQueueInternal(String json, int index) {
        try {
            parseQueue(json);
            if (queue.isEmpty()) return;
            int safe = Math.max(0, Math.min(index, queue.size() - 1));
            playQueueIndex(safe);
        } catch (Exception ignored) {}
    }

    private void playQueueIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        currentQueueIndex = index;
        QueueTrack t = queue.get(index);
        playInternal(t.url, t.id, t.title, t.artist, t.album, t.durationSeconds);
    }

    private void playInternal(String url, String id, String title, String artist, String album, int durationSeconds) {
        stopInternal(false);
        currentId = id == null ? "" : id;
        currentTitle = title == null ? "" : title;
        currentArtist = artist == null ? "" : artist;
        currentAlbum = album == null ? "" : album;
        cachedPositionMs = 0;
        cachedDurationMs = Math.max(0, durationSeconds * 1000);
        prepared = false;
        isPlaying = false;
        updateMetadata();
        updatePlaybackState(PlaybackState.STATE_BUFFERING);
        updateNotification();
        notifyWebPlayerChanged();

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(mp -> {
                prepared = true;
                cachedDurationMs = Math.max(cachedDurationMs, mp.getDuration());
                updateMetadata();
                requestAudioFocus();
                mp.start();
                isPlaying = true;
                beginTelemetry(url);
                updatePlaybackState(PlaybackState.STATE_PLAYING);
                updateNotification();
                notifyWebPlayerChanged();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                recordPlaybackProgress();
                cachedPositionMs = cachedDurationMs;
                isPlaying = false;
                if (repeatMode == 2) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mp.seekTo(0, MediaPlayer.SEEK_CLOSEST);
                        else mp.seekTo(0);
                        cachedPositionMs = 0;
                        mp.start();
                        isPlaying = true;
                        beginTelemetry(url);
                        updatePlaybackState(PlaybackState.STATE_PLAYING);
                        updateNotification();
                    } catch (Exception ignored) {}
                } else {
                    nextInternal(true);
                }
                notifyWebPlayerChanged();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                isPlaying = false;
                prepared = false;
                updatePlaybackState(PlaybackState.STATE_ERROR);
                updateNotification();
                notifyWebPlayerChanged();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception ignored) {
            isPlaying = false;
            prepared = false;
            updatePlaybackState(PlaybackState.STATE_ERROR);
            updateNotification();
        }
    }

    private void nextInternal(boolean fromCompletion) {
        if (queue.isEmpty() || currentQueueIndex < 0) {
            if (fromCompletion) {
                isPlaying = false;
                updatePlaybackState(PlaybackState.STATE_STOPPED);
                updateNotification();
            }
            return;
        }
        int next = currentQueueIndex + 1;
        if (next >= queue.size()) {
            if (repeatMode == 1) next = 0;
            else {
                if (fromCompletion) {
                    isPlaying = false;
                    cachedPositionMs = cachedDurationMs;
                    updatePlaybackState(PlaybackState.STATE_STOPPED);
                    updateNotification();
                }
                return;
            }
        }
        playQueueIndex(next);
    }

    private void previousInternal() {
        if (queue.isEmpty() || currentQueueIndex < 0) return;
        int previous = currentQueueIndex - 1;
        if (previous < 0) {
            if (repeatMode == 1) previous = queue.size() - 1;
            else {
                seekInternal(0);
                return;
            }
        }
        playQueueIndex(previous);
    }

    private void cycleRepeatModeInternal() {
        repeatMode = (repeatMode + 1) % 3;
        updatePlaybackState(isPlaying ? PlaybackState.STATE_PLAYING : (prepared ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED));
        updateNotification();
        runJs("window.androidRepeatModeChanged && window.androidRepeatModeChanged(" + repeatMode + ")");
        notifyWebPlayerChanged();
    }

    private void pauseInternal() {
        if (mediaPlayer == null || !prepared) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            cachedPositionMs = mediaPlayer.getCurrentPosition();
            recordPlaybackProgress();
            isPlaying = false;
            telemetryLastTickAt = 0;
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            updateNotification();
            notifyWebPlayerChanged();
        } catch (IllegalStateException ignored) {}
    }

    private void resumeInternal() {
        if (mediaPlayer == null || !prepared) return;
        try {
            requestAudioFocus();
            mediaPlayer.start();
            isPlaying = true;
            telemetryLastTickAt = SystemClock.elapsedRealtime();
            updatePlaybackState(PlaybackState.STATE_PLAYING);
            updateNotification();
            notifyWebPlayerChanged();
        } catch (IllegalStateException ignored) {}
    }

    private void toggleInternal() {
        if (isPlaying) pauseInternal();
        else resumeInternal();
    }

    private void seekInternal(int positionMs) {
        if (mediaPlayer == null || !prepared) return;
        try {
            int target = Math.max(0, Math.min(positionMs, Math.max(0, cachedDurationMs)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            else mediaPlayer.seekTo(target);
            cachedPositionMs = target;
            updatePlaybackState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            notifyWebPlayerChanged();
        } catch (IllegalStateException ignored) {}
    }

    private void stopInternal(boolean clearTrack) {
        recordPlaybackProgress();
        resetTelemetrySession();
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.reset(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        abandonAudioFocus();
        prepared = false;
        isPlaying = false;
        cachedPositionMs = 0;
        if (clearTrack) {
            currentId = "";
            currentTitle = "";
            currentArtist = "";
            currentAlbum = "";
            cachedDurationMs = 0;
            currentQueueIndex = -1;
            queue.clear();
            if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
            if (mediaSession != null) {
                mediaSession.setMetadata(null);
                mediaSession.setQueue(new ArrayList<>());
            }
        }
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        notifyWebPlayerChanged();
    }

    public final class PlayerBridge {
        @JavascriptInterface
        public void playQueue(String queueJson, int index) {
            runOnUiThread(() -> playQueueInternal(queueJson, index));
        }

        @JavascriptInterface public void toggle() { runOnUiThread(MainActivity.this::toggleInternal); }
        @JavascriptInterface public void pause() { runOnUiThread(MainActivity.this::pauseInternal); }
        @JavascriptInterface public void seekTo(int positionMs) { runOnUiThread(() -> seekInternal(positionMs)); }
        @JavascriptInterface public void next() { runOnUiThread(() -> nextInternal(false)); }
        @JavascriptInterface public void previous() { runOnUiThread(MainActivity.this::previousInternal); }
        @JavascriptInterface public void cycleRepeatMode() { runOnUiThread(MainActivity.this::cycleRepeatModeInternal); }
        @JavascriptInterface public void stop() { runOnUiThread(() -> stopInternal(true)); }

        @JavascriptInterface
        public String getState() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", currentId);
                obj.put("title", currentTitle);
                obj.put("artist", currentArtist);
                obj.put("album", currentAlbum);
                obj.put("positionMs", cachedPositionMs);
                obj.put("durationMs", cachedDurationMs);
                obj.put("playing", isPlaying);
                obj.put("prepared", prepared);
                obj.put("repeatMode", repeatMode);
                obj.put("queueIndex", currentQueueIndex);
                obj.put("queueSize", queue.size());
                return obj.toString();
            } catch (Exception ignored) {
                return "{}";
            }
        }

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

        @JavascriptInterface public String getFontDataUrl() { return fontDataUrl(); }
        @JavascriptInterface public String getFontName() { return fontFileName(); }
        @JavascriptInterface public void chooseFont() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*"); // Include Downloads font files even when provider MIME types are inaccurate.
                startActivityForResult(intent, REQ_FONT);
            });
        }
        @JavascriptInterface public String getTelemetryStatus() { return telemetryStatusJson(); }
        @JavascriptInterface public void testNowPlaying() {
            runOnUiThread(() -> {
                if (!isPlaying || telemetryStreamUrl.isEmpty()) telemetryNowPlayingStatus = "start playing a song first";
                else reportTelemetry(false);
            });
        }
        @JavascriptInterface public void clearFont() {
            runOnUiThread(() -> {
                fontFile().delete();
                getPreferences(MODE_PRIVATE).edit().remove("selected_font_name").apply();
                notifyFontChanged();
            });
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
        } else if (requestCode == REQ_FONT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (saveFontFromUri(data.getData())) notifyFontChanged();
            else runJs("window.androidFontError && window.androidFontError()");
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressTicker);
        try { unregisterReceiver(playbackReceiver); } catch (Exception ignored) {}
        stopInternal(true);
        telemetryExecutor.shutdown();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
