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
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
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
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_FILE_CHOOSER = 41;
    private static final String CHANNEL_ID = "androidnav_playback";
    private static final int NOTIFICATION_ID = 4107;
    private static final String ACTION_TOGGLE = "com.johndsdev.androidnav.TOGGLE";
    private static final String ACTION_STOP = "com.johndsdev.androidnav.STOP";

    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private MediaPlayer mediaPlayer;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private NotificationManager notificationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile String currentId = "";
    private volatile String currentTitle = "";
    private volatile String currentArtist = "";
    private volatile String currentAlbum = "";
    private volatile int cachedPositionMs = 0;
    private volatile int cachedDurationMs = 0;
    private volatile boolean isPlaying = false;
    private volatile boolean prepared = false;

    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = focusChange -> {
        runOnUiThread(() -> {
            if (mediaPlayer == null) return;
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pauseInternal();
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                mediaPlayer.setVolume(0.25f, 0.25f);
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                mediaPlayer.setVolume(1f, 1f);
            }
        });
    };

    private final BroadcastReceiver playbackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (ACTION_TOGGLE.equals(intent.getAction())) toggleInternal();
            if (ACTION_STOP.equals(intent.getAction())) stopInternal(true);
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && prepared) {
                try {
                    cachedPositionMs = mediaPlayer.getCurrentPosition();
                    cachedDurationMs = Math.max(cachedDurationMs, mediaPlayer.getDuration());
                } catch (IllegalStateException ignored) {
                }
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
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String[] accepted = fileChooserParams.getAcceptTypes();
                List<String> mimeTypes = new ArrayList<>();
                if (accepted != null) {
                    for (String accept : accepted) {
                        if (accept == null) continue;
                        String value = accept.trim();
                        if (!value.isEmpty() && value.contains("/") && !value.startsWith(".")) mimeTypes.add(value);
                    }
                }
                if (mimeTypes.size() == 1) {
                    intent.setType(mimeTypes.get(0));
                } else if (mimeTypes.size() > 1) {
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
                } else {
                    intent.setType("*/*");
                }
                startActivityForResult(intent, REQ_FILE_CHOOSER);
                return true;
            }
        });

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
        });
        mediaSession.setActive(true);
        updatePlaybackState(PlaybackState.STATE_NONE);
    }

    private void registerPlaybackReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TOGGLE);
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

    private void updateMetadata() {
        if (mediaSession == null) return;
        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, currentAlbum)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, cachedDurationMs)
                .build();
        mediaSession.setMetadata(metadata);
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, cachedPositionMs, state == PlaybackState.STATE_PLAYING ? 1f : 0f, SystemClock.elapsedRealtime())
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

        Notification.Action toggleAction = new Notification.Action.Builder(
                isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                isPlaying ? "Pause" : "Play",
                broadcastPendingIntent(ACTION_TOGGLE, 2)
        ).build();
        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                broadcastPendingIntent(ACTION_STOP, 3)
        ).build();

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(currentTitle.isEmpty() ? "AndroidNav" : currentTitle)
                .setContentText(currentArtist)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(isPlaying)
                .addAction(toggleAction)
                .addAction(stopAction)
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1))
                .build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void requestAudioFocus() {
        if (audioManager != null) audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private void abandonAudioFocus() {
        if (audioManager != null) audioManager.abandonAudioFocus(audioFocusListener);
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
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(mp -> {
                prepared = true;
                cachedDurationMs = Math.max(cachedDurationMs, mp.getDuration());
                updateMetadata();
                requestAudioFocus();
                mp.start();
                isPlaying = true;
                updatePlaybackState(PlaybackState.STATE_PLAYING);
                updateNotification();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                cachedPositionMs = cachedDurationMs;
                updatePlaybackState(PlaybackState.STATE_STOPPED);
                updateNotification();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                isPlaying = false;
                prepared = false;
                updatePlaybackState(PlaybackState.STATE_ERROR);
                updateNotification();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            isPlaying = false;
            prepared = false;
            updatePlaybackState(PlaybackState.STATE_ERROR);
        }
    }

    private void pauseInternal() {
        if (mediaPlayer == null || !prepared) return;
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            cachedPositionMs = mediaPlayer.getCurrentPosition();
            isPlaying = false;
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            updateNotification();
        } catch (IllegalStateException ignored) {}
    }

    private void resumeInternal() {
        if (mediaPlayer == null || !prepared) return;
        try {
            requestAudioFocus();
            mediaPlayer.start();
            isPlaying = true;
            updatePlaybackState(PlaybackState.STATE_PLAYING);
            updateNotification();
        } catch (IllegalStateException ignored) {}
    }

    private void toggleInternal() {
        if (isPlaying) pauseInternal(); else resumeInternal();
    }

    private void seekInternal(int positionMs) {
        if (mediaPlayer == null || !prepared) return;
        try {
            int target = Math.max(0, Math.min(positionMs, Math.max(0, cachedDurationMs)));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mediaPlayer.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            else mediaPlayer.seekTo(target);
            cachedPositionMs = target;
            updatePlaybackState(isPlaying ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        } catch (IllegalStateException ignored) {}
    }

    private void stopInternal(boolean clearTrack) {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.reset(); } catch (Exception ignored) {}
            mediaPlayer.release();
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
            if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
            if (mediaSession != null) mediaSession.setMetadata(null);
        }
        updatePlaybackState(PlaybackState.STATE_STOPPED);
    }

    public final class PlayerBridge {
        @JavascriptInterface public void play(String url, String id, String title, String artist, String album, int durationSeconds) { runOnUiThread(() -> playInternal(url, id, title, artist, album, durationSeconds)); }
        @JavascriptInterface public void toggle() { runOnUiThread(MainActivity.this::toggleInternal); }
        @JavascriptInterface public void pause() { runOnUiThread(MainActivity.this::pauseInternal); }
        @JavascriptInterface public void seekTo(int positionMs) { runOnUiThread(() -> seekInternal(positionMs)); }
        @JavascriptInterface public void stop() { runOnUiThread(() -> stopInternal(true)); }
        @JavascriptInterface public String getState() {
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
                return obj.toString();
            } catch (Exception e) { return "{}"; }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER && fileChooserCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressTicker);
        try { unregisterReceiver(playbackReceiver); } catch (Exception ignored) {}
        stopInternal(true);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
