/*
 * Copyright (C) 2015 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.carbonrom.settings.device;

import android.app.ActivityManagerNative;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraAccessException;
import android.media.session.MediaSessionLegacyHelper;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;

import com.android.internal.os.DeviceKeyHandler;
import com.android.internal.util.ArrayUtils;

public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = KeyHandler.class.getSimpleName();
    private static final String ACTION_DISMISS_KEYGUARD =
            "com.android.keyguard.action.DISMISS_KEYGUARD_SECURELY";

    // Supported scancodes
    private static final int MODE_TOTAL_SILENCE = 600;
    private static final int MODE_ALARMS_ONLY = 601;
    private static final int MODE_PRIORITY_ONLY = 602;
    private static final int MODE_NONE = 603;

    private static final SparseIntArray sSupportedSliderModes = new SparseIntArray();
    static {
        sSupportedSliderModes.put(MODE_TOTAL_SILENCE, Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);
        sSupportedSliderModes.put(MODE_ALARMS_ONLY, Settings.Global.ZEN_MODE_ALARMS);
        sSupportedSliderModes.put(MODE_PRIORITY_ONLY,
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);
        sSupportedSliderModes.put(MODE_NONE, Settings.Global.ZEN_MODE_OFF);
    }

    private final Context mContext;
    private final PowerManager mPowerManager;
    private KeyguardManager mKeyguardManager;
    private final NotificationManager mNotificationManager;
    private SensorManager mSensorManager;
    private CameraManager mCameraManager;
    private String mRearCameraId;
    private boolean mTorchEnabled;
    private Sensor mProximitySensor;
    private Vibrator mVibrator;
    WakeLock mProximityWakeLock;
    WakeLock mGestureWakeLock;
    private int mProximityTimeOut;
    private boolean mProximityWakeSupported;

    public KeyHandler(Context context) {
        mContext = context;
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mNotificationManager
                = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        final Resources resources = mContext.getResources();
        mProximityTimeOut = resources.getInteger(
                com.android.internal.R.integer.config_proximityCheckTimeout);
        mProximityWakeSupported = resources.getBoolean(
                com.android.internal.R.bool.config_proximityCheckOnWake);

        if (mProximityWakeSupported) {
            mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            mProximityWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "ProximityWakeLock");
        }

        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (mVibrator == null || !mVibrator.hasVibrator()) {
            mVibrator = null;
        }

    }

    private void ensureKeyguardManager() {
        if (mKeyguardManager == null) {
            mKeyguardManager =
                    (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        }
    }

    public boolean handleKeyEvent(KeyEvent event) {
        int scanCode = event.getScanCode();
        boolean isSliderModeSupported = sSupportedSliderModes.indexOfKey(scanCode) >= 0;
        if (!isSliderModeSupported) {
            return false;
        }

        if (event.getAction() != KeyEvent.ACTION_UP) {
            return true;
        }

        if (isSliderModeSupported) {
            mNotificationManager.setZenMode(sSupportedSliderModes.get(scanCode), null, TAG);
            doHapticFeedback();
        } else if (!mEventHandler.hasMessages(GESTURE_REQUEST)) {
            Message msg = getMessageForKeyEvent(scanCode);
            boolean defaultProximity = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_proximityCheckOnWakeEnabledByDefault);
            boolean proximityWakeCheckEnabled = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.PROXIMITY_ON_WAKE, defaultProximity ? 1 : 0) == 1;
            if (mProximityWakeSupported && proximityWakeCheckEnabled && mProximitySensor != null) {
                mEventHandler.sendMessageDelayed(msg, mProximityTimeOut);
                processEvent(scanCode);
            } else {
                mEventHandler.sendMessage(msg);
            }
        }
        return true;
    }

    private void dispatchMediaKeyWithWakeLockToMediaSession(int keycode) {
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper != null) {
            KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
            helper.sendMediaButtonEvent(event, true);
            event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
            helper.sendMediaButtonEvent(event, true);
        } else {
            Log.w(TAG, "Unable to send media key event");
        }
    }

    private void startActivitySafely(Intent intent) {
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            UserHandle user = new UserHandle(UserHandle.USER_CURRENT);
            mContext.startActivityAsUser(intent, null, user);
        } catch (ActivityNotFoundException e) {
            // Ignore
        }
    }

}
