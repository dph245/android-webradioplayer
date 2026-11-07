package de.webradioplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

@OptIn(markerClass = UnstableApi.class)
public final class RadioService extends Service {
    private static final String TAG = "Webradio";
    private static final String PLAY = "de.webradioplayer.PLAY";
    private static final String STOP = "de.webradioplayer.STOP";
    private static final String CHANNEL = "radio";
    private static final int NOTIFICATION_ID = 1;
    private static final long STREAM_TIMEOUT_MS = 20_000;

    record Playback(Station station, int message, boolean active) {
        static final Playback IDLE = new Playback(null, R.string.ready, false);
    }

    final class LocalBinder extends Binder {
        RadioService service() {
            return RadioService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> finishPlayback(R.string.stream_error);
    private final Set<Consumer<Playback>> listeners = new HashSet<>();
    private Playback playback = Playback.IDLE;
    private int lastStartId;
    private AudioManager audio;
    private AudioAttributes attributes;
    private AudioFocusRequest focus;
    private boolean hasFocus;
    private MediaSession session;
    private ExoPlayer player;

    static Intent playIntent(Context context, Station station) {
        return new Intent(context, RadioService.class).setAction(PLAY)
                .putExtra("title", station.title()).putExtra("url", station.url());
    }

    static Intent stopIntent(Context context) {
        return new Intent(context, RadioService.class).setAction(STOP);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this::onFocusChanged, handler)
                .build();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.playback_channel),
                        NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, TAG);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPause() { finishPlayback(R.string.stopped); }
            @Override public void onStop() { finishPlayback(R.string.stopped); }
        }, handler);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    void addListener(Consumer<Playback> listener) {
        listeners.add(listener);
        listener.accept(playback);
    }

    void removeListener(Consumer<Playback> listener) {
        listeners.remove(listener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent != null && PLAY.equals(intent.getAction())) {
            play(new Station(intent.getStringExtra("title"), intent.getStringExtra("url")));
        } else {
            finishPlayback(R.string.stopped);
        }
        return START_NOT_STICKY;
    }

    private void play(Station station) {
        if (playback.active() && station.equals(playback.station())) {
            return;
        }
        releasePlayer();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, station.title())
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, station.title())
                .build());
        session.setActive(true);
        publish(station, R.string.connecting, PlaybackState.STATE_BUFFERING);
        startForeground(NOTIFICATION_ID, notification(station));

        if (!hasFocus) {
            hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!hasFocus) {
                finishPlayback(R.string.audio_busy);
                return;
            }
        }

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        ExoPlayer current = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), false)
                .build();
        player = current;
        current.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (current != player) {
                    return;
                }
                switch (state) {
                    case Player.STATE_IDLE -> { }
                    case Player.STATE_BUFFERING -> {
                        scheduleTimeout();
                        if (playback.message() != R.string.connecting) {
                            publish(station, R.string.buffering, PlaybackState.STATE_BUFFERING);
                        }
                    }
                    case Player.STATE_READY -> {
                        handler.removeCallbacks(timeout);
                        publish(station, R.string.playing, PlaybackState.STATE_PLAYING);
                    }
                    case Player.STATE_ENDED -> finishPlayback(R.string.stopped);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (current == player) {
                    Log.w(TAG, error.getErrorCodeName() + " for " + station.title());
                    finishPlayback(R.string.stream_error);
                }
            }
        });
        current.setMediaItem(MediaItem.fromUri(station.url()));
        current.setPlayWhenReady(true);
        current.prepare();
    }

    private void scheduleTimeout() {
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, STREAM_TIMEOUT_MS);
    }

    private Notification notification(Station station) {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 0, stopIntent(this),
                PendingIntent.FLAG_IMMUTABLE);
        session.setSessionActivity(openApp);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(station.title())
                .setContentText(getString(R.string.app_name))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop),
                        getString(R.string.stop), stop).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0))
                .build();
    }

    private void publish(Station station, int message, int mediaState) {
        boolean active = mediaState == PlaybackState.STATE_PLAYING
                || mediaState == PlaybackState.STATE_BUFFERING;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(active ? PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP
                        | PlaybackState.ACTION_PLAY_PAUSE : 0)
                .setState(mediaState, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        mediaState == PlaybackState.STATE_PLAYING ? 1f : 0f)
                .build());
        playback = new Playback(station, message, active);
        for (Consumer<Playback> listener : listeners) {
            listener.accept(playback);
        }
    }

    private void onFocusChanged(int change) {
        if (hasFocus && (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            finishPlayback(R.string.stopped);
        }
    }

    private void finishPlayback(int message) {
        releasePlayer();
        abandonFocus();
        publish(playback.station(), message, PlaybackState.STATE_STOPPED);
        session.setActive(false);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (lastStartId != 0) {
            stopSelfResult(lastStartId);
        }
    }

    private void releasePlayer() {
        handler.removeCallbacks(timeout);
        if (player != null) {
            ExoPlayer previous = player;
            player = null;
            previous.release();
        }
    }

    private void abandonFocus() {
        if (hasFocus) {
            hasFocus = false;
            audio.abandonAudioFocusRequest(focus);
        }
    }

    @Override
    public void onDestroy() {
        releasePlayer();
        abandonFocus();
        session.release();
        super.onDestroy();
    }
}
