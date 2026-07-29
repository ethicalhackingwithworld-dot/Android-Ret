package com.example.cloudutility;

import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

/**
 * FirebaseHelper - Singleton class for Firebase Realtime Database connection
 * Features:
 * - Singleton pattern for single Firebase instance
 * - Database reference management
 * - Connection state monitoring
 * - Auto-reconnection handling
 */
public class FirebaseHelper {
    
    private static final String TAG = "FirebaseHelper";
    private static FirebaseHelper instance;
    private FirebaseDatabase firebaseDatabase;
    private boolean isInitialized = false;
    
    // Firebase configuration
    // In production, these should be in a secure config file or environment variables
    private static final String DATABASE_URL = "https://android-ret-default-rtdb.firebaseio.com";
    private static final String API_KEY = "AIzaSyBy543gJuSXRVt8V2m7WECKupK-CBtsHyE";
    private static final String APP_ID = "1:1071645764594:android:17e70db52ba7fd4488596b";
    private static final String PROJECT_ID = "android-ret";
    
    // Private constructor for singleton
    private FirebaseHelper() {
        initializeFirebase();
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized FirebaseHelper getInstance() {
        if (instance == null) {
            instance = new FirebaseHelper();
        }
        return instance;
    }
    
    /**
     * Initialize Firebase with configuration
     */
    private void initializeFirebase() {
        try {
            // Check if Firebase is already initialized
            if (FirebaseApp.getApps(FirebaseHelper.class.getClassLoader()).isEmpty()) {
                
                FirebaseOptions options = new FirebaseOptions.Builder()
                    .setApiKey"AIzaSyBy543gJuSXRVt8V2m7WECKupK-CBtsHyE";
                    .setApplicationId"1:1071645764594:web:ab3c4c06d4bdabc988596b";
                    .setDatabaseUrl"https://android-ret-default-rtdb.firebaseio.com";
                    .setProjectId"android-ret";
                    .build();
                
                FirebaseApp.initializeApp(FirebaseHelper.class.getClassLoader(), options);
                Log.d(TAG, "Firebase initialized with custom options");
            }
            
            // Get database instance with persistence enabled
            firebaseDatabase = FirebaseDatabase.getInstance();
            firebaseDatabase.setPersistenceEnabled(true);
            
            // Set up connection monitoring
            DatabaseReference connectedRef = firebaseDatabase.getReference(".info/connected");
            connectedRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                    boolean connected = Boolean.TRUE.equals(
                        snapshot.getValue(Boolean.class));
                    if (connected) {
                        Log.d(TAG, "Firebase connected");
                        isInitialized = true;
                    } else {
                        Log.w(TAG, "Firebase disconnected");
                        isInitialized = false;
                    }
                }
                
                @Override
                public void onCancelled(com.google.firebase.database.DatabaseError error) {
                    Log.e(TAG, "Firebase connection listener cancelled", 
                        error.toException());
                    isInitialized = false;
                }
            });
            
            Log.d(TAG, "Firebase initialization successful");
            
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed", e);
            isInitialized = false;
        }
    }
    
    /**
     * Get Firebase Database instance
     */
    public FirebaseDatabase getDatabase() {
        if (firebaseDatabase == null) {
            throw new IllegalStateException(
                "Firebase Database not initialized. Call initializeFirebase() first.");
        }
        return firebaseDatabase;
    }
    
    /**
     * Get reference to a specific path
     */
    public DatabaseReference getReference(String path) {
        if (firebaseDatabase == null) {
            throw new IllegalStateException(
                "Firebase Database not initialized. Call initializeFirebase() first.");
        }
        return firebaseDatabase.getReference(path);
    }
    
    /**
     * Get device-specific reference
     */
    public DatabaseReference getDeviceReference(String deviceId) {
        return getReference("devices").child(deviceId);
    }
    
    /**
     * Check if Firebase is initialized and connected
     */
    public boolean isConnected() {
        return isInitialized && firebaseDatabase != null;
    }
    
    /**
     * Get the root reference for all devices
     */
    public DatabaseReference getDevicesReference() {
        return getReference("devices");
    }
    
    /**
     * Get reference for device tasks
     */
    public DatabaseReference getDeviceTasksReference(String deviceId) {
        return getDeviceReference(deviceId).child("tasks");
    }
    
    /**
     * Get reference for device logs
     */
    public DatabaseReference getDeviceLogsReference(String deviceId) {
        return getDeviceReference(deviceId).child("logs");
    }
    
    /**
     * Get reference for device telemetry
     */
    public DatabaseReference getDeviceTelemetryReference(String deviceId) {
        return getDeviceReference(deviceId).child("telemetry");
    }
    
    /**
     * Get reference for device location
     */
    public DatabaseReference getDeviceLocationReference(String deviceId) {
        return getDeviceReference(deviceId).child("location");
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        try {
            if (firebaseDatabase != null) {
                firebaseDatabase.goOffline();
                Log.d(TAG, "Firebase database gone offline");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
        }
    }
}
