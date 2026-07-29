package com.example.cloudutility;

import android.app.Application;
import android.util.Log;

/**
 * Application class for Cloud Utility
 */
public class CloudUtilityApplication extends Application {
    
    private static final String TAG = "CloudUtilityApp";
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Application initializing");
        
        // Initialize Firebase Helper
        FirebaseHelper.getInstance();
        
        Log.d(TAG, "Application initialized");
    }
}
