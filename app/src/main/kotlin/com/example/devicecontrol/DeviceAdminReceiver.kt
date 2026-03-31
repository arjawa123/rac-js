package com.example.devicecontrol

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        // Device Admin diaktifkan
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Device Admin dinonaktifkan
    }
}
