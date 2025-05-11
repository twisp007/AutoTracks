package de.dennisguse.opentracks.detectors

import android.location.Location
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import de.dennisguse.opentracks.services.TrackRecordingService // Ensure this import is correct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

// --- State Definition ---
enum class VEHICLE_STATE {
    UNKNOWN,        // Initial state or insufficient data
    STATIONARY,     // Device is likely stationary or moving at non-vehicular speeds
    IN_VEHICLE,     // Device is likely within a moving vehicle
    EXITING_VEHICLE // Hysteresis state to prevent flapping during brief stops
}

// --- Algorithm Parameters (Tunable) ---
object VehicleStateDetector {
    private const val TAG = "VehicleStateDetector"

    var currentStateMessages = MutableStateFlow("Detector Initialized")

    // Accuracy and Windowing
    private const val MAX_ACCEPTED_ACCURACY_METERS = 100.0f
    private const val ANALYSIS_WINDOW_DURATION_MS = 15 * 1000L
    private const val MIN_WINDOW_SIZE_FOR_CLASSIFICATION = 3

    // Speed Thresholds (m/s)
    private const val VEHICLE_ENTRY_SPEED_THRESHOLD_MPS = 6.0f
    private const val VEHICLE_EXIT_SPEED_THRESHOLD_MPS = 4.5f

    // Hysteresis Durations (ms)
    private const val HYSTERESIS_ENTRY_DURATION_MS = 7 * 1000L
    private const val HYSTERESIS_EXIT_DURATION_MS = 10 * 1000L
    private const val EXIT_TO_STATIONARY_CONFIRM_DURATION_MS = 15 * 1000L

    private val STOPPED_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30)

    // --- State Variables ---
    private val _currentVehicleState = MutableLiveData<VEHICLE_STATE>(VEHICLE_STATE.UNKNOWN)
    val currentVehicleState: LiveData<VEHICLE_STATE> = _currentVehicleState

    private val locationBuffer = ArrayDeque<Location>()

    private var entryConditionStartTime: Long? = null
    private var exitConditionStartTime: Long? = null
    private var exitingToStationaryStartTime: Long? = null

    private var lastLocationTimestamp: Long? = null
    private var lastLocation: Location? = null

    private var trackRecordingService: TrackRecordingService? = null

    private var shouldStartRecording: Boolean = false
    private var shouldStopRecording: Boolean = false

    // --- Marker Creation Logic for Zero Speed ---
    // Flag to prevent creating multiple markers for the same continuous stop at 0 speed.
    private var markerCreatedForZeroSpeedStop: Boolean = false

    fun setTrackRecordingService(service: TrackRecordingService?) {
        trackRecordingService = service
        Log.d(TAG, "TrackRecordingService instance ${if (service != null) "set" else "cleared"}.")
    }

    fun processLocation(location: Location) {
        val currentTime = location.time

        if (!location.hasAccuracy() || location.accuracy > MAX_ACCEPTED_ACCURACY_METERS) {
            currentStateMessages.update { "Ignoring inaccurate location: ${location.accuracy}m at $currentTime" }
            return
        }

        if (!location.hasSpeed()) {
            currentStateMessages.update { "Ignoring location without speed at $currentTime" }
            return
        }
        if (location.speed < 0 || location.speed > 100) { // 100 m/s = 360 km/h
            currentStateMessages.update { "Ignoring location with implausible speed: ${location.speed} m/s" }
            return
        }

        lastLocationTimestamp = currentTime
        lastLocation = location
        val currentSpeed = location.speed

        // --- Marker creation logic for zero speed ---
        // Check current speed from the validated location.
        // Using a small epsilon for floating point comparison with 0 might be more robust,
        // but for GPS speeds, direct 0.0f comparison is often sufficient.
        // Let's consider speed < 0.1 m/s as effectively zero for this purpose.
        val effectivelyZeroSpeed = currentSpeed < 0.1f

        if (!effectivelyZeroSpeed) {
            // If speed is clearly above zero, reset the flag. This means movement has resumed or started.
            if (markerCreatedForZeroSpeedStop) {
                Log.d(TAG, "Movement detected (speed: $currentSpeed m/s), resetting zero-speed marker flag.")
                markerCreatedForZeroSpeedStop = false
            }
        } else { // currentSpeed is effectively zero
            if (!markerCreatedForZeroSpeedStop) {
                trackRecordingService?.let { service ->
                    if (service.isRecording()) {
                        val currentState = _currentVehicleState.value
                        // Create marker if we were considered moving or just stopping.
                        // This prevents creating a marker if the app starts and speed is already 0.
                        if (currentState == VEHICLE_STATE.IN_VEHICLE || currentState == VEHICLE_STATE.EXITING_VEHICLE) {
                            Log.i(TAG, "Speed is effectively 0 ($currentSpeed m/s). Creating a marker. Current state: $currentState")
                            service.createMarker() // Attempt to create a marker
                            markerCreatedForZeroSpeedStop = true // Set flag to prevent immediate re-creation for this stop
                            currentStateMessages.update { "Marker created (Speed 0)" }
                        }
                    }
                }
            }
            // else: marker already created for this stop, or service not available/recording.
        }

        // --- Add to Data Buffer (Sliding Window) ---
        locationBuffer.addFirst(location)
        while (locationBuffer.isNotEmpty() && (currentTime - locationBuffer.last.time > ANALYSIS_WINDOW_DURATION_MS)) {
            locationBuffer.removeLast()
        }

        if (locationBuffer.size < MIN_WINDOW_SIZE_FOR_CLASSIFICATION) {
            currentStateMessages.update { "Not enough data points: ${locationBuffer.size}/${MIN_WINDOW_SIZE_FOR_CLASSIFICATION}" }
            return
        }

        val averageSpeed = locationBuffer.map { it.speed }.average().toFloat()
        val windowTimeSpan = currentTime - locationBuffer.last.time

        val previousState = _currentVehicleState.value
        updateStateBasedOnSpeed(averageSpeed, currentTime, windowTimeSpan)

        if (previousState != _currentVehicleState.value) {
            Log.i(TAG, "State changed from $previousState to ${_currentVehicleState.value} (Avg Speed: $averageSpeed m/s over ${windowTimeSpan / 1000}s, Buffer: ${locationBuffer.size}pts)")
            currentStateMessages.update { "State: ${_currentVehicleState.value} (Avg Speed: $averageSpeed m/s)" }
        }
    }

    private fun updateStateBasedOnSpeed(averageSpeed: Float, currentTime: Long, windowTimeSpan: Long) {
        val currentStateVal = _currentVehicleState.value ?: VEHICLE_STATE.UNKNOWN

        when (currentStateVal) {
            VEHICLE_STATE.UNKNOWN -> {
                if (averageSpeed >= VEHICLE_ENTRY_SPEED_THRESHOLD_MPS) {
                    if (windowTimeSpan >= HYSTERESIS_ENTRY_DURATION_MS / 2) {
                        _currentVehicleState.postValue(VEHICLE_STATE.IN_VEHICLE)
                        resetTimers()
                        shouldStartRecording = true
                        markerCreatedForZeroSpeedStop = false // Reset if we start moving
                    }
                } else {
                    if (windowTimeSpan >= HYSTERESIS_EXIT_DURATION_MS / 2) {
                        _currentVehicleState.postValue(VEHICLE_STATE.STATIONARY)
                        resetTimers()
                        shouldStopRecording = true
                    }
                }
            }
            VEHICLE_STATE.STATIONARY -> {
                if (averageSpeed >= VEHICLE_ENTRY_SPEED_THRESHOLD_MPS) {
                    if (entryConditionStartTime == null) entryConditionStartTime = currentTime
                    if (currentTime - (entryConditionStartTime ?: currentTime) >= HYSTERESIS_ENTRY_DURATION_MS) {
                        _currentVehicleState.postValue(VEHICLE_STATE.IN_VEHICLE)
                        resetTimers()
                        shouldStartRecording = true
                        markerCreatedForZeroSpeedStop = false // Reset if we start moving
                    }
                    exitConditionStartTime = null
                    exitingToStationaryStartTime = null
                } else {
                    entryConditionStartTime = null
                }
            }
            VEHICLE_STATE.IN_VEHICLE -> {
                if (averageSpeed < VEHICLE_EXIT_SPEED_THRESHOLD_MPS) {
                    if (exitConditionStartTime == null) exitConditionStartTime = currentTime
                    if (currentTime - (exitConditionStartTime ?: currentTime) >= HYSTERESIS_EXIT_DURATION_MS) {
                        _currentVehicleState.postValue(VEHICLE_STATE.EXITING_VEHICLE)
                        exitingToStationaryStartTime = currentTime
                        entryConditionStartTime = null
                    }
                    // No change to markerCreatedForZeroSpeedStop here, handled by direct speed check
                } else { // Speed is high, remain IN_VEHICLE
                    exitConditionStartTime = null
                    exitingToStationaryStartTime = null
                    markerCreatedForZeroSpeedStop = false // If speed picked up significantly, reset flag
                }
            }
            VEHICLE_STATE.EXITING_VEHICLE -> {
                if (averageSpeed >= VEHICLE_ENTRY_SPEED_THRESHOLD_MPS) {
                    if (entryConditionStartTime == null) entryConditionStartTime = currentTime
                    if (currentTime - (entryConditionStartTime ?: currentTime) >= HYSTERESIS_ENTRY_DURATION_MS) {
                        _currentVehicleState.postValue(VEHICLE_STATE.IN_VEHICLE)
                        resetTimers()
                        markerCreatedForZeroSpeedStop = false // Reset if we go back to IN_VEHICLE
                    }
                    exitingToStationaryStartTime = null
                } else if (averageSpeed < VEHICLE_EXIT_SPEED_THRESHOLD_MPS) {
                    entryConditionStartTime = null
                    if (exitingToStationaryStartTime != null &&
                        (currentTime - exitingToStationaryStartTime!! >= EXIT_TO_STATIONARY_CONFIRM_DURATION_MS)
                    ) {
                        _currentVehicleState.postValue(VEHICLE_STATE.STATIONARY)
                        resetTimers()
                        shouldStopRecording = true
                        // If speed is 0 when transitioning to STATIONARY, marker logic in processLocation would have run.
                    }
                } else {
                    resetTimers()
                }
            }
        }
        onStateChanged(_currentVehicleState.value ?: currentStateVal)
    }

    private fun onStateChanged(newState: VEHICLE_STATE) {
        if (trackRecordingService == null) {
            return
        }

        if (shouldStartRecording && newState == VEHICLE_STATE.IN_VEHICLE) {
            trackRecordingService?.let { service ->
                if (!service.isRecording()) {
                    Log.i(TAG, "Requesting to start recording due to state: $newState")
                    service.startNewTrack()
                    markerCreatedForZeroSpeedStop = false // Reset flag when new track starts
                } else {
                    Log.i(TAG, "State is IN_VEHICLE, but service is already recording.")
                }
            }
            shouldStartRecording = false
        }

        if (shouldStopRecording && newState == VEHICLE_STATE.STATIONARY) {
            trackRecordingService?.let { service ->
                if (service.isRecording()) {
                    Log.i(TAG, "Requesting to stop recording due to state: $newState")
                    service.endCurrentTrack()
                } else {
                    Log.i(TAG, "State is STATIONARY, but service is not currently recording.")
                }
            }
            shouldStopRecording = false
        }
    }

    fun checkIfStoppedDueToTimeout(currentTimeMillis: Long): Boolean {
        val lastTs = lastLocationTimestamp ?: return false
        val currentStateVal = _currentVehicleState.value
        if ((currentStateVal == VEHICLE_STATE.IN_VEHICLE || currentStateVal == VEHICLE_STATE.EXITING_VEHICLE) &&
            (currentTimeMillis - lastTs > STOPPED_TIMEOUT_MS)
        ) {
            val previousState = _currentVehicleState.value
            Log.i(TAG, "State changed to STATIONARY due to lack of updates (timeout). Last update: ${(currentTimeMillis - lastTs)/1000}s ago. Previous state: $previousState")
            _currentVehicleState.postValue(VEHICLE_STATE.STATIONARY)
            resetTimers()
            locationBuffer.clear()
            shouldStopRecording = true
            currentStateMessages.update { "State: STATIONARY (Timeout)" }
            if (previousState != VEHICLE_STATE.STATIONARY) {
                onStateChanged(VEHICLE_STATE.STATIONARY)
            }
            return true
        }
        return false
    }

    fun getLastKnownLocation(): Location? = lastLocation
    fun getLastKnownLocationTimestamp(): Long? = lastLocationTimestamp

    private fun resetTimers() {
        entryConditionStartTime = null
        exitConditionStartTime = null
        exitingToStationaryStartTime = null
    }

    fun resetState() {
        Log.i(TAG, "VehicleStateDetector reset to initial state.")
        _currentVehicleState.postValue(VEHICLE_STATE.UNKNOWN)
        locationBuffer.clear()
        resetTimers()
        lastLocationTimestamp = null
        lastLocation = null
        shouldStartRecording = false
        shouldStopRecording = false
        markerCreatedForZeroSpeedStop = false // Reset zero-speed marker flag
        currentStateMessages.update { "Detector Reset to UNKNOWN" }
    }
}
