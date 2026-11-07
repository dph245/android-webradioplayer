package de.webradioplayer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final String TAG = "Webradio";
    private static final int PICK_PLAYLIST = 1;
    private static final String SELECTED_URL = "selectedUrl";
    private static final String SELECTED_TITLE = "selectedTitle";
    private static final ExecutorService IMPORTS = Executors.newSingleThreadExecutor();

    private final List<Button> stationButtons = new ArrayList<>();
    private List<Station> stations = Collections.emptyList();
    private int selectedIndex;
    private int playlistStatus = R.string.ready;
    private ScrollView scroll;
    private LinearLayout stationList;
    private TextView status;
    private Button importButton;
    private Button startButton;
    private Button stopButton;
    private AudioManager audio;
    private RadioService service;
    private boolean bound;
    private RadioService.Playback playback = RadioService.Playback.IDLE;
    private final Consumer<RadioService.Playback> playbackListener = this::onPlaybackChanged;
    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RadioService.LocalBinder) binder).service();
            service.addListener(playbackListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            onPlaybackChanged(RadioService.Playback.IDLE);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        audio = getSystemService(AudioManager.class);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        try {
            stations = Playlist.load(this);
            if (stations.isEmpty()) {
                playlistStatus = R.string.no_stations;
            }
        } catch (IOException | XmlPullParserException error) {
            Log.w(TAG, "Unable to read streams.xspf", error);
            playlistStatus = R.string.playlist_error;
        }
        if (state != null && state.containsKey(SELECTED_URL)) {
            selectedIndex = Math.max(0, findStation(
                    new Station(state.getString(SELECTED_TITLE), state.getString(SELECTED_URL))));
        }

        buildScreen();
        updateStatus();
    }

    private void buildScreen() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(getColor(R.color.background));
        padScreen(screen, 0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            screen.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets insets = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                padScreen(view, insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }
        setContentView(screen);

        TextView heading = label(29, R.color.text_primary);
        heading.setText(R.string.app_name);
        if (Build.VERSION.SDK_INT >= 28) {
            heading.setAccessibilityHeading(true);
        }
        screen.addView(heading);

        status = label(15, R.color.accent);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(0, dp(4), 0, dp(14));
        screen.addView(status);

        LinearLayout stationHeader = new LinearLayout(this);
        stationHeader.setGravity(Gravity.CENTER_VERTICAL);
        stationHeader.setPadding(0, 0, 0, dp(8));
        screen.addView(stationHeader);
        TextView stationHeading = label(14, R.color.text_secondary);
        stationHeading.setText(R.string.stations);
        if (Build.VERSION.SDK_INT >= 28) {
            stationHeading.setAccessibilityHeading(true);
        }
        stationHeader.addView(stationHeading, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        importButton = radioButton(getText(R.string.load_playlist), 48);
        importButton.setOnClickListener(view -> choosePlaylist());
        stationHeader.addView(importButton);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        stationList = new LinearLayout(this);
        stationList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(stationList);
        populateStations();

        LinearLayout playbackRow = new LinearLayout(this);
        playbackRow.setPadding(0, dp(6), 0, 0);
        screen.addView(playbackRow);
        startButton = addControl(playbackRow, R.string.start, 0,
                () -> startStation(selectedIndex));
        startButton.setSelected(true);
        stopButton = addControl(playbackRow, R.string.stop, dp(8),
                () -> startService(RadioService.stopIntent(this)));

        LinearLayout volumeRow = new LinearLayout(this);
        screen.addView(volumeRow);
        addControl(volumeRow, R.string.quieter, 0,
                () -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0));
        addControl(volumeRow, R.string.louder, dp(8),
                () -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0));
    }

    private void populateStations() {
        stationList.removeAllViews();
        stationButtons.clear();
        for (int index = 0; index < stations.size(); index += 2) {
            LinearLayout row = new LinearLayout(this);
            stationList.addView(row);
            addStationButton(row, index, 0);
            if (index + 1 < stations.size()) {
                addStationButton(row, index + 1, dp(8));
            } else {
                addToRow(row, new View(this), dp(8));
            }
        }
    }

    private void padScreen(View view, int left, int top, int right, int bottom) {
        view.setPadding(left + dp(16), top + dp(10), right + dp(16), bottom + dp(4));
    }

    private TextView label(int textSize, int color) {
        TextView label = new TextView(this);
        label.setTextSize(textSize);
        label.setTextColor(getColor(color));
        return label;
    }

    private void addStationButton(LinearLayout row, int index, int marginStart) {
        Button button = radioButton(stations.get(index).title(), 56);
        button.setOnClickListener(view -> startStation(index));
        stationButtons.add(button);
        addToRow(row, button, marginStart);
    }

    private Button addControl(LinearLayout row, int text, int marginStart, Runnable action) {
        Button button = radioButton(getText(text), 48);
        button.setOnClickListener(view -> action.run());
        addToRow(row, button, marginStart);
        return button;
    }

    private void addToRow(LinearLayout row, View view, int marginStart) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMarginStart(marginStart);
        params.bottomMargin = dp(8);
        row.addView(view, params);
    }

    private Button radioButton(CharSequence text, int height) {
        int[][] states = {{android.R.attr.state_selected}, {}};
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(height));
        button.setMinimumHeight(dp(height));
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setStateListAnimator(null);
        button.setTextColor(new ColorStateList(states,
                new int[]{getColor(R.color.on_accent), getColor(R.color.text_primary)}));

        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(new ColorStateList(states,
                new int[]{getColor(R.color.accent), getColor(R.color.surface)}));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(getColor(R.color.ripple)), background, null));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startStation(int index) {
        try {
            startForegroundService(RadioService.playIntent(this, stations.get(index)));
            selectedIndex = index;
        } catch (IllegalStateException error) {
            Log.w(TAG, "Unable to start playback service", error);
            Toast.makeText(this, R.string.audio_busy, Toast.LENGTH_SHORT).show();
        }
    }

    private void onPlaybackChanged(RadioService.Playback state) {
        playback = state;
        updateStatus();
    }

    private int findStation(Station station) {
        if (station == null) {
            return -1;
        }
        int exact = stations.indexOf(station);
        if (exact >= 0) {
            return exact;
        }
        for (int index = 0; index < stations.size(); index++) {
            if (stations.get(index).url().equals(station.url())) {
                return index;
            }
        }
        return -1;
    }

    private void updateStatus() {
        Station station = playback.station();
        int playingIndex = findStation(station);
        if (playingIndex >= 0) {
            selectedIndex = playingIndex;
        }
        if (playback.active() || playingIndex >= 0) {
            status.setText(getString(playback.message(), station.title()));
        } else if (stations.isEmpty()) {
            status.setText(playlistStatus);
        } else {
            status.setText(getString(R.string.ready, stations.get(selectedIndex).title()));
        }
        int highlighted = playback.active() ? playingIndex : selectedIndex;
        for (int index = 0; index < stationButtons.size(); index++) {
            stationButtons.get(index).setSelected(index == highlighted);
        }
        setControlEnabled(startButton, !playback.active() && !stations.isEmpty());
        setControlEnabled(stopButton, playback.active());
    }

    private void choosePlaylist() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_PLAYLIST);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_PLAYLIST || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri source = data.getData();
        setControlEnabled(importButton, false);
        IMPORTS.execute(() -> {
            try {
                List<Station> imported = Playlist.importFrom(getApplicationContext(), source);
                runOnUiThread(() -> playlistImported(imported));
            } catch (IOException | XmlPullParserException | SecurityException error) {
                Log.w(TAG, "Unable to import playlist", error);
                runOnUiThread(() -> playlistImported(null));
            }
        });
    }

    private void playlistImported(List<Station> imported) {
        if (isDestroyed()) {
            return;
        }
        setControlEnabled(importButton, true);
        if (imported == null) {
            Toast.makeText(this, R.string.import_error, Toast.LENGTH_LONG).show();
            return;
        }
        Station selected = stations.isEmpty() ? null : stations.get(selectedIndex);
        stations = imported;
        selectedIndex = Math.max(0, findStation(selected));
        populateStations();
        scroll.scrollTo(0, 0);
        updateStatus();
        Toast.makeText(this, R.string.playlist_loaded, Toast.LENGTH_SHORT).show();
    }

    private void setControlEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.5f);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (!stations.isEmpty()) {
            Station selected = stations.get(selectedIndex);
            state.putString(SELECTED_URL, selected.url());
            state.putString(SELECTED_TITLE, selected.title());
        }
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, RadioService.class), playbackConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) {
            service.removeListener(playbackListener);
            service = null;
        }
        if (bound) {
            unbindService(playbackConnection);
            bound = false;
        }
        super.onStop();
    }
}
