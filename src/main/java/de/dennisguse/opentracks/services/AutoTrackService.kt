package de.dennisguse.opentracks.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer // Required for observing LiveData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import de.dennisguse.opentracks.R // Ensure R is correctly imported for resources like icons
import de.dennisguse.opentracks.detectors.VEHICLE_STATE // Import for VEHICLE_STATE enum
import de.dennisguse.opentracks.detectors.VehicleStateDetector
import de.dennisguse.opentracks.introduction.IntroductionActivity // Corrected: Main entry point

class AutoTrackService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var systemNotificationManager: NotificationManager

    private var trackRecordingService: TrackRecordingService? = null
    private var isServiceBound = false
    private lateinit var periodicCheckHandler: Handler
    private lateinit var timeoutCheckRunnable: Runnable

    // Stores the last text displayed in the notification to avoid redundant updates
    private var lastNotificationText: String? = null

    private val vehicleStateObserver = Observer<VEHICLE_STATE> { state ->
        val stateName = state?.toString() ?: "UNKNOWN"
        // Construct the new notification text
        val newNotificationText = "Vehicle State: $stateName"
        updateNotificationContent(newNotificationText)
    }

    private val trackRecordingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val binder = service as TrackRecordingService.Binder
                trackRecordingService = binder.service

                if (trackRecordingService != null) {
                    VehicleStateDetector.setTrackRecordingService(trackRecordingService!!)
                    isServiceBound = true
                    Log.i(TAG, "Successfully connected to TrackRecordingService.")
                    updateNotificationContent("Auto-tracking active.") // Initial update
                } else {
                    Log.e(TAG, "Failed to get TrackRecordingService instance from binder.")
                    updateNotificationContent("Error: Recording service unavailable.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to TrackRecordingService: ${e.message}", e)
                updateNotificationContent("Error: Connection failed.")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "Disconnected from TrackRecordingService.")
            updateNotificationContent("Disconnected from recording service.")
            VehicleStateDetector.setTrackRecordingService(null)
            trackRecordingService = null
            isServiceBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AutoTrackService creating...")
        systemNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    VehicleStateDetector.processLocation(location)
                }
            }
        }

        val serviceIntent = Intent(this, TrackRecordingService::class.java)
        try {
            bindService(serviceIntent, trackRecordingServiceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to TrackRecordingService: ${e.message}", e)
            updateNotificationContent("Error: Binding failed.")
        }

        periodicCheckHandler = Handler(Looper.getMainLooper())
        timeoutCheckRunnable = object : Runnable {
            override fun run() {
                VehicleStateDetector.checkIfStoppedDueToTimeout(System.currentTimeMillis())
                periodicCheckHandler.postDelayed(this, TIMEOUT_CHECK_INTERVAL_MS)
            }
        }

        VehicleStateDetector.resetState()
        VehicleStateDetector.currentVehicleState.observeForever(vehicleStateObserver)

        Log.i(TAG, "AutoTrackService created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "AutoTrackService onStartCommand.")
        createNotificationChannel()
        val initialNotificationText = "Initializing auto-tracking..."
        lastNotificationText = initialNotificationText // Initialize lastNotificationText
        val notification = createNotification(initialNotificationText)

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
        }

        startLocationUpdates()

        periodicCheckHandler.removeCallbacks(timeoutCheckRunnable)
        periodicCheckHandler.postDelayed(timeoutCheckRunnable, TIMEOUT_CHECK_INTERVAL_MS)

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "OpenTracks Auto Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors vehicle movement for automatic track recording."
            }
            systemNotificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, IntroductionActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OpenTracks Auto Tracking")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_logo_color_24dp)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Updates the notification content, but only if the new text is different from the last displayed text.
     * @param newText The new text to display in the notification.
     */
    private fun updateNotificationContent(newText: String) {
        // Only update the notification if the text content has actually changed.
        if (newText == lastNotificationText) {
            // Log.d(TAG, "Notification content unchanged, not re-posting: \"$newText\"") // Optional: for debugging
            return
        }

        if (::systemNotificationManager.isInitialized) {
            val notification = createNotification(newText)
            systemNotificationManager.notify(NOTIFICATION_ID, notification)
            lastNotificationText = newText // Store the newly displayed text
            Log.d(TAG, "Notification updated with: \"$newText\"")
        } else {
            Log.w(TAG, "SystemNotificationManager not initialized, cannot update notification.")
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_LOCATION_UPDATE_INTERVAL_MS)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted. AutoTrackService cannot start location updates.")
            updateNotificationContent("Error: Location permission missing.")
            stopSelf()
            return
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.i(TAG, "Requested location updates for auto-tracking.")
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException requesting location updates: ${se.message}", se)
            updateNotificationContent("Error: Location security issue.")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Exception requesting location updates: ${e.message}", e)
            updateNotificationContent("Error: Starting location tracking failed.")
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "AutoTrackService destroying...")
        super.onDestroy()

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing location updates: ${e.message}")
        }

        if (isServiceBound) {
            try {
                unbindService(trackRecordingServiceConnection)
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding service: ${e.message}")
            }
            VehicleStateDetector.setTrackRecordingService(null)
            trackRecordingService = null
            isServiceBound = false
        }

        periodicCheckHandler.removeCallbacks(timeoutCheckRunnable)
        VehicleStateDetector.currentVehicleState.removeObserver(vehicleStateObserver)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.i(TAG, "AutoTrackService destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AutoTrackService"
        private const val NOTIFICATION_ID = 12345
        private const val NOTIFICATION_CHANNEL_ID = "opentracks_auto_track_svc_channel"

        private const val LOCATION_UPDATE_INTERVAL_MS = 10 * 1000L
        private const val MIN_LOCATION_UPDATE_INTERVAL_MS = 5 * 1000L
        private const val TIMEOUT_CHECK_INTERVAL_MS = 30 * 1000L
    }
}
