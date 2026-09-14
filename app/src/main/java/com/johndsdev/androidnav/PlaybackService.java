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

import android.app.Service;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.wifi.WifiManager;
import android.media.AudioFocusRequest;

/** Owns playback independently of any Activity or WebView. All mutations run on the main thread. */
public class PlaybackService extends Service {
    private static final String BACKGROUND_FILE = "androidnav_background.jpg";
    private static final String CHANNEL_ID = "androidnav_playback";
    private static final int NOTIFICATION_ID = 4107;

    private static final String ACTION_PREVIOUS = "com.johndsdev.androidnav.PREVIOUS";
    private static final String ACTION_TOGGLE = "com.johndsdev.androidnav.TOGGLE";
    private static final String ACTION_NEXT = "com.johndsdev.androidnav.NEXT";
    private static final String ACTION_REPEAT = "com.johndsdev.androidnav.REPEAT";
    private static final String ACTION_STOP = "com.johndsdev.androidnav.STOP";
    private static final String CUSTOM_REPEAT = "com.johndsdev.androidnav.REPEAT_CUSTOM";

    private MediaPlayer mediaPlayer;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private NotificationManager notificationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

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

    private volatile String snapshot = "{}";
    private boolean buffering, playWhenReady, resumeOnFocusGain;
    private String playbackError = "";
    private AudioFocusRequest focusRequest;
    private WifiManager.WifiLock wifiLock;
    private final Binder binder = new LocalBinder();
    public final class LocalBinder extends Binder { PlaybackService getService() { return PlaybackService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }
    public String getState() { return snapshot; }
    public void playQueue(String json, int index) {
        // Called through the bound UI on the main thread, so queues never cross Binder's size limit.
        startService(new Intent(this, PlaybackService.class));
        playQueueInternal(json, index);
    }
    public void command(String command, int value) {
        switch (command) {
            case "toggle": toggleInternal(); break;
            case "pause": pauseInternal(); break;
            case "seek": seekInternal(value); break;
            case "next": nextInternal(false); break;
            case "previous": previousInternal(); break;
            case "repeat": cycleRepeatModeInternal(); break;
            case "stop": stopInternal(true); break;
            case "artwork": updateMetadata(); updateNotification(); break;
        }
        publishState();
    }
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener = change -> handler.post(() -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            boolean shouldResume = playWhenReady;
            pauseInternal();
            resumeOnFocusGain = shouldResume;
        } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pauseInternal();
            abandonAudioFocus();
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if(mediaPlayer != null) mediaPlayer.setVolume(.25f,.25f);
        } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if(mediaPlayer != null) mediaPlayer.setVolume(1f,1f);
            if(resumeOnFocusGain) { resumeOnFocusGain=false; resumeInternal(); }
        }
    });
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pauseInternal(); }
    };
    private final Runnable progressTicker = new Runnable() {
        @Override public void run() {
            if(mediaPlayer != null && prepared) {
                try { cachedPositionMs=mediaPlayer.getCurrentPosition(); } catch(IllegalStateException ignored) { }
            }
            publishState();
            handler.postDelayed(this,250);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        notificationManager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(wifi != null) { wifiLock=wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"AndroidNav:stream"); wifiLock.setReferenceCounted(false); }
        createNotificationChannel();
        createMediaSession();
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisyReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisyReceiver,filter);
        handler.post(progressTicker);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?null:intent.getAction();
        if(ACTION_PREVIOUS.equals(action))previousInternal();
        else if(ACTION_TOGGLE.equals(action))toggleInternal();
        else if(ACTION_NEXT.equals(action))nextInternal(false);
        else if(ACTION_REPEAT.equals(action))cycleRepeatModeInternal();
        else if(ACTION_STOP.equals(action))stopInternal(true);
        publishState();
        return START_NOT_STICKY;
    }
    private void holdWifi(boolean hold) {
        if(wifiLock==null)return;
        if(hold&&!wifiLock.isHeld())wifiLock.acquire();
        else if(!hold&&wifiLock.isHeld())wifiLock.release();
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("AndroidNav playback controls");
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
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

    private String repeatLabel() {
        if (repeatMode == 2) return "Repeat one";
        if (repeatMode == 1) return "Repeat all";
        return "Repeat off";
    }

    private void updateSessionQueue() {
        if (mediaSession == null) return;
        List<MediaSession.QueueItem> items = new ArrayList<>();
        for (int i = Math.max(0,currentQueueIndex-25); i < Math.min(queue.size(),Math.max(0,currentQueueIndex)+26); i++) {
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
        MediaMetadata.Builder builder = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, currentId)
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, currentAlbum)
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
                CUSTOM_REPEAT, repeatLabel(), android.R.drawable.ic_popup_sync).build();
        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(actions)
                .addCustomAction(repeatAction)
                .setState(state, cachedPositionMs, state == PlaybackState.STATE_PLAYING ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build();
        mediaSession.setPlaybackState(playbackState);
    }

    private PendingIntent broadcastPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, requestCode, intent, flags);
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
                android.R.drawable.ic_popup_sync, repeatLabel(), broadcastPendingIntent(ACTION_REPEAT, 5)).build();

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
                .setOngoing(isPlaying || buffering)
                .setDeleteIntent(broadcastPendingIntent(ACTION_STOP,6))
                .addAction(previousAction)
                .addAction(toggleAction)
                .addAction(nextAction)
                .addAction(repeatAction)
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        if (art != null) builder.setLargeIcon(art);
        if(isPlaying || buffering) startForeground(NOTIFICATION_ID,builder.build());
        else { stopForeground(false); notificationManager.notify(NOTIFICATION_ID,builder.build()); }
        publishState();
    }

    private boolean requestAudioFocus() {
        if(audioManager==null)return false;
        int result;
        if(Build.VERSION.SDK_INT>=26) {
            if(focusRequest==null)focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(audioFocusListener,handler).build();
            result=audioManager.requestAudioFocus(focusRequest);
        } else result=audioManager.requestAudioFocus(audioFocusListener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
        return result==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }
    private void abandonAudioFocus() {
        if(audioManager==null)return;
        if(Build.VERSION.SDK_INT>=26 && focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);
        else audioManager.abandonAudioFocus(audioFocusListener);
    }
    private void parseQueue(String json) throws Exception {
        JSONArray array = new JSONArray(json == null ? "[]" : json);
        List<QueueTrack> parsed = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            parsed.add(new QueueTrack(
                    o.optString("url", ""),
                    o.optString("id", ""),
                    o.optString("title", "Untitled"),
                    o.optString("artist", "Unknown artist"),
                    o.optString("album", ""),
                    o.optInt("durationSeconds", 0)
            ));
        }
        if(parsed.isEmpty())throw new IllegalArgumentException("Queue is empty");
        queue.clear(); queue.addAll(parsed);
        updateSessionQueue();
    }

    private void playQueueInternal(String json, int index) {
        try {
            parseQueue(json);
            if (queue.isEmpty()) return;
            int safe = Math.max(0, Math.min(index, queue.size() - 1));
            playQueueIndex(safe);
        } catch (Exception e) { failPlayback("Could not load the playback queue."); }
    }

    private void playQueueIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        currentQueueIndex = index;
        updateSessionQueue();
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
        buffering = true;
        playWhenReady = true;
        playbackError = "";
        holdWifi(true);
        updateMetadata();
        updatePlaybackState(PlaybackState.STATE_BUFFERING);
        updateNotification();

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(mp -> {
                if(mp!=mediaPlayer)return;
                prepared = true;
                buffering = false;
                cachedDurationMs = Math.max(cachedDurationMs, mp.getDuration());
                updateMetadata();
                if(playWhenReady)resumeInternal();
                else { holdWifi(false); updatePlaybackState(PlaybackState.STATE_PAUSED); updateNotification(); }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                if(mp!=mediaPlayer)return;
                cachedPositionMs = cachedDurationMs;
                isPlaying = false;
                if (repeatMode == 2) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) mp.seekTo(0, MediaPlayer.SEEK_CLOSEST);
                        else mp.seekTo(0);
                        cachedPositionMs = 0;
                        mp.start();
                        isPlaying = true;
                        updatePlaybackState(PlaybackState.STATE_PLAYING);
                        updateNotification();
                    } catch (Exception ignored) {}
                } else {
                    nextInternal(true);
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if(mp==mediaPlayer)failPlayback("Could not play this track. Check your connection and tap play to retry.");
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            failPlayback("Could not open this track. Tap play to retry.");
        }
    }
    private void failPlayback(String message) {
        stopInternal(false);
        playbackError=message;
        updatePlaybackState(PlaybackState.STATE_ERROR);
        updateNotification();
        publishState();
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
                    playWhenReady = false;
                    holdWifi(false);
                    abandonAudioFocus();
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
        publishState();
    }

    private void pauseInternal() {
        playWhenReady=false;
        resumeOnFocusGain=false;
        if (mediaPlayer == null || !prepared) { publishState(); return; }
        try {
            if (mediaPlayer.isPlaying()) mediaPlayer.pause();
            cachedPositionMs = mediaPlayer.getCurrentPosition();
            isPlaying = false;
            holdWifi(false);
            updatePlaybackState(PlaybackState.STATE_PAUSED);
            updateNotification();
        } catch (IllegalStateException ignored) {}
    }

    private void resumeInternal() {
        playWhenReady=true;
        if(mediaPlayer==null) { if(currentQueueIndex>=0)playQueueIndex(currentQueueIndex); return; }
        if(!prepared)return;
        try {
            if(!requestAudioFocus()) {
                playWhenReady=false;
                playbackError="Another app is using audio. Tap play to try again.";
                holdWifi(false);
                updatePlaybackState(PlaybackState.STATE_PAUSED);
                updateNotification();
                return;
            }
            playbackError="";
            holdWifi(true);
            mediaPlayer.start();
            isPlaying=true;
            updatePlaybackState(PlaybackState.STATE_PLAYING);
            updateNotification();
        } catch(IllegalStateException e) { failPlayback("Playback stopped. Tap play to retry."); }
    }
    private void toggleInternal() {
        if(isPlaying || (buffering && playWhenReady))pauseInternal();
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
        } catch (IllegalStateException ignored) {}
    }

    private void stopInternal(boolean clearTrack) {
        buffering=false;
        playWhenReady=false;
        resumeOnFocusGain=false;
        holdWifi(false);
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
            playbackError="";
            stopForeground(true);
            stopSelf();
            if (notificationManager != null) notificationManager.cancel(NOTIFICATION_ID);
            if (mediaSession != null) {
                mediaSession.setMetadata(null);
                mediaSession.setQueue(new ArrayList<>());
            }
        }
        updatePlaybackState(PlaybackState.STATE_STOPPED);
    }

    private void publishState() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("buffering", buffering);
                obj.put("error", playbackError);
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
                snapshot = obj.toString();
            } catch (Exception ignored) {
                snapshot = "{}";
            }
        }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if(!isPlaying&&!buffering)stopSelf();
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(noisyReceiver);
        stopInternal(true);
        mediaSession.setActive(false);
        mediaSession.release();
        super.onDestroy();
    }
}
