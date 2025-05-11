/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package de.dennisguse.opentracks;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer; // Import AndroidX Lifecycle Observer
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.button.MaterialButton;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import de.dennisguse.opentracks.data.ContentProviderUtils;
import de.dennisguse.opentracks.data.models.Track;
import de.dennisguse.opentracks.databinding.TrackListBinding;
import de.dennisguse.opentracks.detectors.VEHICLE_STATE; // Import VEHICLE_STATE
import de.dennisguse.opentracks.detectors.VehicleStateDetector; // Import VehicleStateDetector
import de.dennisguse.opentracks.sensors.GpsStatusValue;
import de.dennisguse.opentracks.services.AutoTrackService;
import de.dennisguse.opentracks.services.MissingPermissionException;
import de.dennisguse.opentracks.services.RecordingStatus;
import de.dennisguse.opentracks.services.TrackRecordingService;
import de.dennisguse.opentracks.services.TrackRecordingServiceConnection;
import de.dennisguse.opentracks.settings.PreferencesUtils;
import de.dennisguse.opentracks.settings.SettingsActivity;
import de.dennisguse.opentracks.settings.UnitSystem;
import de.dennisguse.opentracks.share.ShareUtils;
import de.dennisguse.opentracks.ui.TrackListAdapter;
import de.dennisguse.opentracks.ui.aggregatedStatistics.AggregatedStatisticsActivity;
import de.dennisguse.opentracks.ui.aggregatedStatistics.ConfirmDeleteDialogFragment;
import de.dennisguse.opentracks.ui.markers.MarkerListActivity;
import de.dennisguse.opentracks.ui.util.ActivityUtils;
import de.dennisguse.opentracks.util.IntentDashboardUtils;
import de.dennisguse.opentracks.util.IntentUtils;
import de.dennisguse.opentracks.util.PermissionRequester;

/**
 * An activity displaying a list of tracks.
 *
 * @author Leif Hendrik Wilden
 */
public class TrackListActivity extends AbstractTrackDeleteActivity implements ConfirmDeleteDialogFragment.ConfirmDeleteCaller {

    private static final String TAG = TrackListActivity.class.getSimpleName();

    // The following are set in onCreate
    private TrackRecordingServiceConnection recordingStatusConnection;
    private TrackListAdapter adapter;

    private TrackListBinding viewBinding;

    // Preferences
    private UnitSystem unitSystem = UnitSystem.defaultUnitSystem();

    private GpsStatusValue gpsStatusValue = TrackRecordingService.STATUS_GPS_DEFAULT;
    private RecordingStatus recordingStatus = TrackRecordingService.STATUS_DEFAULT;

    // Callback when an item is selected in the contextual action mode
    private final ActivityUtils.ContextualActionModeCallback contextualActionModeCallback = new ActivityUtils.ContextualActionModeCallback() {

        @Override
        public void onPrepare(Menu menu, int[] positions, long[] trackIds, boolean showSelectAll) {
            boolean isSingleSelection = trackIds.length == 1;

            //Ensure viewBinding is not null before accessing its members
            if (viewBinding == null) return;

            viewBinding.bottomAppBar.performHide(true);
            viewBinding.trackListFabAction.setVisibility(View.INVISIBLE);

            menu.findItem(R.id.list_context_menu_edit).setVisible(isSingleSelection);
            menu.findItem(R.id.list_context_menu_select_all).setVisible(showSelectAll);
        }

        @Override
        public boolean onClick(int itemId, int[] positions, long[] trackIds) {
            return handleContextItem(itemId, trackIds);
        }

        @Override
        public void onDestroy() {
            //Ensure viewBinding is not null before accessing its members
            if (viewBinding == null) return;
            viewBinding.trackListFabAction.setVisibility(View.VISIBLE);
            viewBinding.bottomAppBar.performShow(true);
        }
    };

    private final OnSharedPreferenceChangeListener sharedPreferenceChangeListener = (sharedPreferences, key) -> {
        if (PreferencesUtils.isKey(R.string.stats_units_key, key)) {
            unitSystem = PreferencesUtils.getUnitSystem();
            if (adapter != null) {
                adapter.updateUnitSystem(unitSystem);
            }
        }
        if (key != null) {
            runOnUiThread(() -> {
                TrackListActivity.this.invalidateOptionsMenu();
                loadData();
            });
        }
    };

    // Menu items

    private String searchQuery;

    private final TrackRecordingServiceConnection.Callback bindChangedCallback = (service, unused) -> {
        service.getRecordingStatusObservable()
                .observe(TrackListActivity.this, this::onRecordingStatusChanged);

        service.getGpsStatusObservable()
                .observe(TrackListActivity.this, this::onGpsStatusChanged);

        updateGpsMenuItem(true, recordingStatus.isRecording());
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // viewBinding is initialized by getRootView() which is called by super.onCreate()

        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

        requestRequiredPermissions();

        // Start the AutoTrackService if it's intended to run independently
        // If its lifecycle is purely tied to TrackListActivity, this might be managed differently
        // For now, we assume it's started here to begin its monitoring.
        Intent serviceIntent = new Intent(this, AutoTrackService.class);
        ContextCompat.startForegroundService(this, serviceIntent);


        recordingStatusConnection = new TrackRecordingServiceConnection(bindChangedCallback);

        // Ensure viewBinding is not null before setting listeners
        if (viewBinding == null) {
            Log.e(TAG, "ViewBinding is null in onCreate after super.onCreate()");
            // Consider finishing the activity or showing an error if viewBinding is crucial here
            finish();
            return;
        }

        viewBinding.aggregatedStatsButton.setOnClickListener((view) -> startActivity(IntentUtils.newIntent(this, AggregatedStatisticsActivity.class)));
        viewBinding.sensorStartButton.setOnClickListener((view) -> {
            LocationManager locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null && !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } else {
                if (gpsStatusValue.isGpsStarted()) {
                    recordingStatusConnection.stopService(this);
                } else {
                    startSensorsOrRecording((service, connection) -> service.tryStartSensors());
                }
            }
        });

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        adapter = new TrackListAdapter(this, viewBinding.rvTrackList, recordingStatus, unitSystem);
        viewBinding.rvTrackList.setLayoutManager(layoutManager);
        viewBinding.rvTrackList.setAdapter(adapter);

        viewBinding.trackListFabAction.setOnClickListener((view) -> {
            if (recordingStatus.isRecording()) {
                Toast.makeText(TrackListActivity.this, getString(R.string.hold_to_stop), Toast.LENGTH_LONG).show();
                return;
            }

            // Not Recording -> Recording
            Log.i(TAG, "FAB Action: Starting new track recording.");
            // updateGpsMenuItem(false, true); // This will be handled by onRecordingStatusChanged
            startSensorsOrRecording((service, connection) -> {
                Track.Id trackId = service.startNewTrack(); // This call will trigger RecordingStatusObservable

                Intent newIntent = IntentUtils.newIntent(TrackListActivity.this, TrackRecordingActivity.class);
                newIntent.putExtra(TrackRecordingActivity.EXTRA_TRACK_ID, trackId);
                startActivity(newIntent);
            });
        });
        viewBinding.trackListFabAction.setOnLongClickListener((view) -> {
            if (!recordingStatus.isRecording()) {
                return false;
            }

            // Recording -> Stop
            Log.i(TAG, "FAB Action: Stopping current track recording.");
            ActivityUtils.vibrate(this, Duration.ofSeconds(1));
            // updateGpsMenuItem(false, false); // This will be handled by onRecordingStatusChanged
            recordingStatusConnection.stopRecording(TrackListActivity.this); // This will trigger RecordingStatusObservable
            // UI updates for FAB are handled in setFloatButton via onRecordingStatusChanged
            return true;
        });

        setSupportActionBar(viewBinding.trackListToolbar);
        if (adapter != null) { // Check adapter for null before setting callback
            adapter.setActionModeCallback(contextualActionModeCallback);
        }
    }

    private void requestRequiredPermissions() {
        PermissionRequester.ALL.requestPermissionsIfNeeded(this, this, null, (requester) -> Toast.makeText(this, R.string.permission_recording_failed, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onStart() {
        super.onStart();

        PreferencesUtils.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        if (recordingStatusConnection != null) { // Check for null
            recordingStatusConnection.bind(this);
        }


        // Observe the LiveData from VehicleStateDetector
        if (viewBinding != null && viewBinding.vehicleStateTextview != null) {
            // Assuming VehicleStateDetector.INSTANCE is how you access the singleton
            VehicleStateDetector.INSTANCE.getCurrentVehicleState().observe(this, new Observer<VEHICLE_STATE>() {
                @Override
                public void onChanged(VEHICLE_STATE vehicleState) {
                    if (vehicleState != null) {
                        String stateText = vehicleState.toString();
                        viewBinding.vehicleStateTextview.setText(stateText);
                    } else {
                        viewBinding.vehicleStateTextview.setText("Vehicle State Not Available");
                    }
                }
            });
        } else {
            Log.e(TAG, "ViewBinding or vehicleStateTextview is null in onStart when trying to observe vehicle state.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Update UI
        this.invalidateOptionsMenu();
        // loadData() is called here, which is good for when returning to the activity.
        // The change in onRecordingStatusChanged will handle immediate updates if already resumed.
        loadData();

        // Float button state is updated via onRecordingStatusChanged -> setFloatButton
        // but call it here too to ensure correct initial state on resume if status hasn't changed.
        setFloatButton();
    }

    @Override
    protected void onStop() {
        super.onStop();

        PreferencesUtils.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
        if (recordingStatusConnection != null) { // Check for null
            recordingStatusConnection.unbind(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Consider if AutoTrackService should be stopped here or if it runs independently.
        // If it's meant to run only when TrackListActivity is active or initiated by it:
        // Intent serviceIntent = new Intent(this, AutoTrackService.class);
        // stopService(serviceIntent);

        viewBinding = null;
        recordingStatusConnection = null;
        adapter = null;
    }

    @Override
    protected View getRootView() {
        viewBinding = TrackListBinding.inflate(getLayoutInflater());

        if (viewBinding != null && viewBinding.trackListSearchView != null && viewBinding.trackListSearchView.getEditText() != null) {
            viewBinding.trackListSearchView.getEditText().setOnEditorActionListener((v, actionId, event) -> {
                if (viewBinding.trackListSearchView.getEditText() != null) {
                    searchQuery = viewBinding.trackListSearchView.getEditText().getText().toString();
                }
                if(viewBinding.trackListSearchView != null) { // Check again before hiding
                    viewBinding.trackListSearchView.hide();
                }
                loadData();
                return true;
            });
        } else {
            Log.e(TAG, "ViewBinding or search components are null in getRootView.");
        }
        return viewBinding.getRoot();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.track_list, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        updateGpsMenuItem(gpsStatusValue.isGpsStarted(), recordingStatus.isRecording());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.track_list_markers) {
            startActivity(IntentUtils.newIntent(this, MarkerListActivity.class));
            return true;
        } else if (itemId == R.id.track_list_settings) {
            startActivity(IntentUtils.newIntent(this, SettingsActivity.class));
            return true;
        } else if (itemId == R.id.track_list_help) {
            startActivity(IntentUtils.newIntent(this, HelpActivity.class));
            return true;
        } else if (itemId == R.id.track_list_about) {
            startActivity(IntentUtils.newIntent(this, AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadData() {
        if (viewBinding == null) {
            Log.e(TAG, "ViewBinding is null in loadData. Cannot load track data.");
            return;
        }

        // For Material SearchBar, text might be set differently or observed.
        // This assumes trackListToolbar is a Material Components SearchBar or similar.
        if (viewBinding.trackListToolbar != null) { // Check toolbar for null
            viewBinding.trackListToolbar.setText(searchQuery);
            viewBinding.trackListToolbar.setHint(Objects.requireNonNullElseGet(searchQuery, () -> getString(R.string.app_name)));
        }


        Cursor tracks = null;
        try {
            ContentProviderUtils contentProviderUtils = new ContentProviderUtils(this);
            tracks = contentProviderUtils.searchTracks(searchQuery); // This re-queries the database
        } catch (Exception e) {
            Log.e(TAG, "Error loading track data from ContentProviderUtils", e);
            // Optionally, show a message to the user
        }


        if (adapter != null) {
            adapter.swapData(tracks); // This should update the RecyclerView
        } else {
            Log.w(TAG, "Adapter is null in loadData. Cannot swap data.");
        }
    }

    @Override
    protected void onDeleteConfirmed() {
        // Do nothing
    }

    @Override
    public void onDeleteFinished() {
        // Do nothing
    }

    @Nullable
    @Override
    protected Track.Id getRecordingTrackId() {
        return recordingStatus != null ? recordingStatus.trackId() : null; // Null check for recordingStatus
    }

    private void updateGpsMenuItem(boolean isGpsStarted, boolean isRecording) {
        if (viewBinding == null || viewBinding.sensorStartButton == null) {
            Log.e(TAG, "ViewBinding or sensorStartButton is null in updateGpsMenuItem.");
            return;
        }
        MaterialButton startGpsMenuItem = viewBinding.sensorStartButton;
        startGpsMenuItem.setVisibility(!isRecording ? View.VISIBLE : View.INVISIBLE);
        if (!isRecording) {
            startGpsMenuItem.setIcon(AppCompatResources.getDrawable(this, isGpsStarted ? gpsStatusValue.icon : R.drawable.ic_gps_off_24dp));
            if (startGpsMenuItem.getIcon() instanceof AnimatedVectorDrawable) {
                ((AnimatedVectorDrawable) startGpsMenuItem.getIcon()).start();
            }
        }
    }

    private boolean handleContextItem(int itemId, long... longTrackIds) {
        Track.Id[] trackIds = new Track.Id[longTrackIds.length];
        for (int i = 0; i < longTrackIds.length; i++) {
            trackIds[i] = new Track.Id(longTrackIds[i]);
        }

        if (itemId == R.id.list_context_menu_show_on_map) {
            IntentDashboardUtils.showTrackOnMap(this, false, trackIds);
            return true;
        }

        if (itemId == R.id.list_context_menu_share) {
            Intent intent = ShareUtils.newShareFileIntent(this, trackIds);
            intent = Intent.createChooser(intent, null);
            startActivity(intent);
            return true;
        }

        if (itemId == R.id.list_context_menu_edit) {
            Intent intent = IntentUtils.newIntent(this, TrackEditActivity.class)
                    .putExtra(TrackEditActivity.EXTRA_TRACK_ID, trackIds[0]);
            startActivity(intent);
            return true;
        }

        if (itemId == R.id.list_context_menu_delete) {
            deleteTracks(trackIds);
            return true;
        }

        if (itemId == R.id.list_context_menu_aggregated_stats) {
            Intent intent = IntentUtils.newIntent(this, AggregatedStatisticsActivity.class)
                    .putParcelableArrayListExtra(AggregatedStatisticsActivity.EXTRA_TRACK_IDS, new ArrayList<>(Arrays.asList(trackIds)));
            startActivity(intent);
            return true;
        }

        if (itemId == R.id.list_context_menu_select_all) {
            if (adapter != null) {
                adapter.setAllSelected(true);
            }
            return false;
        }

        return false;
    }

    public void onGpsStatusChanged(GpsStatusValue newStatus) {
        if (newStatus != null) { // Null check for newStatus
            gpsStatusValue = newStatus;
            // recordingStatus can be null here if service is not yet bound or status not received
            updateGpsMenuItem(true, recordingStatus != null && recordingStatus.isRecording());
        }
    }

    private void setFloatButton() {
        if (viewBinding == null || viewBinding.trackListFabAction == null) {
            Log.e(TAG, "ViewBinding or trackListFabAction is null in setFloatButton.");
            return;
        }
        if (recordingStatus == null) { // Null check for recordingStatus
            Log.w(TAG, "RecordingStatus is null in setFloatButton. Defaulting FAB to non-recording state.");
            viewBinding.trackListFabAction.setImageResource(R.drawable.ic_baseline_record_24);
            viewBinding.trackListFabAction.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.red_dark));
            return;
        }
        viewBinding.trackListFabAction.setImageResource(recordingStatus.isRecording() ? R.drawable.ic_baseline_stop_24 : R.drawable.ic_baseline_record_24);
        viewBinding.trackListFabAction.setBackgroundTintList(ContextCompat.getColorStateList(this, recordingStatus.isRecording() ? R.color.opentracks : R.color.red_dark));
    }

    private void onRecordingStatusChanged(RecordingStatus newStatus) {
        if (newStatus == null) { // Null check for newStatus
            Log.w(TAG, "Received null RecordingStatus in onRecordingStatusChanged.");
            return;
        }

        boolean TwasRecording = (recordingStatus != null && recordingStatus.isRecording()); // Previous state
        recordingStatus = newStatus; // Update to new status

        Log.d(TAG, "onRecordingStatusChanged: New status is recording: " + recordingStatus.isRecording() + ", Was recording: " + TwasRecording);

        setFloatButton(); // Update FAB based on the new status
        if (adapter != null) {
            adapter.updateRecordingStatus(recordingStatus); // Inform adapter of new status
        }

        // If recording has just started (transitioned from not recording to recording)
        if (recordingStatus.isRecording() && !TwasRecording) {
            Log.i(TAG, "Recording has just started. Reloading track list data.");
            loadData(); // This will re-query and call adapter.swapData()
        }
        // Also consider reloading if recording just stopped, to reflect any final track updates.
        // However, typically onResume handles list refresh when returning to this screen.
        // If a track is stopped and the user stays on this screen, this is where you might reload.
        else if (!recordingStatus.isRecording() && TwasRecording) {
            Log.i(TAG, "Recording has just stopped. Reloading track list data to reflect final state.");
            loadData();
        }
    }

    private void startSensorsOrRecording(TrackRecordingServiceConnection.Callback callback) {
        try {
            TrackRecordingServiceConnection.executeForeground(this, callback);
        } catch (MissingPermissionException e) {
            Toast.makeText(this, R.string.permission_recording_failed, Toast.LENGTH_LONG).show();
        }
    }

    // Add these to your strings.xml if not already present:
    // <string name="vehicle_state_label">Vehicle State: %1$s</string>
    // <string name="vehicle_state_unavailable">Vehicle state: Unavailable</string>
}
