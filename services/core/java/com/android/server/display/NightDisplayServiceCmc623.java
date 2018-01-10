/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.display;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.MathUtils;
import android.util.Slog;
import android.view.animation.AnimationUtils;

import com.android.internal.app.NightDisplayController;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.TimeZone;

import com.android.internal.R;

/**
 * Tints the display at night.
 */
public final class NightDisplayServiceCmc623 extends SystemService
        implements NightDisplayController.Callback {

    private static final String TAG = "NightDisplayServiceCmc623";

    private static final String TEMPERATURE_FILE = "/sys/class/mdnie/mdnie/mdnie_temp";

    private final Handler mHandler;
    private final AtomicBoolean mIgnoreAllColorMatrixChanges = new AtomicBoolean();
    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(final boolean enabled) {
            // Turn off all night mode display stuff while device is in VR mode.
            mIgnoreAllColorMatrixChanges.set(enabled);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    final DisplayTransformManager dtm =
                            getLocalService(DisplayTransformManager.class);
                    if (enabled) {
                        setMdnieTemp(false);
                    } else if (mController != null && mController.isActivated()) {
                        applyTint(true);
                    }
                }
            });
        }
    };

    private int mCurrentUser = UserHandle.USER_NULL;
    private ContentObserver mUserSetupObserver;
    private boolean mBootCompleted;

    private NightDisplayController mController;
    private Boolean mIsActivated;
    private AutoMode mAutoMode;

    public NightDisplayServiceCmc623(Context context) {
        super(context);
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onStart() {
        // Nothing to publish.
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase >= PHASE_SYSTEM_SERVICES_READY) {
            final IVrManager vrManager = IVrManager.Stub.asInterface(
                    getBinderService(Context.VR_SERVICE));
            if (vrManager != null) {
                try {
                    vrManager.registerListener(mVrStateCallbacks);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to register VR mode state listener: " + e);
                }
            }
        }

        if (phase >= PHASE_BOOT_COMPLETED) {
            mBootCompleted = true;

            // Register listeners now that boot is complete.
            if (mCurrentUser != UserHandle.USER_NULL && mUserSetupObserver == null) {
                setUp();
            }
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        super.onStartUser(userHandle);

        if (mCurrentUser == UserHandle.USER_NULL) {
            onUserChanged(userHandle);
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);

        onUserChanged(userHandle);
    }

    @Override
    public void onStopUser(int userHandle) {
        super.onStopUser(userHandle);

        if (mCurrentUser == userHandle) {
            onUserChanged(UserHandle.USER_NULL);
        }
    }

    private void onUserChanged(int userHandle) {
        final ContentResolver cr = getContext().getContentResolver();

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (mUserSetupObserver != null) {
                cr.unregisterContentObserver(mUserSetupObserver);
                mUserSetupObserver = null;
            } else if (mBootCompleted) {
                tearDown();
            }
        }

        mCurrentUser = userHandle;

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (!isUserSetupCompleted(cr, mCurrentUser)) {
                mUserSetupObserver = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (isUserSetupCompleted(cr, mCurrentUser)) {
                            cr.unregisterContentObserver(this);
                            mUserSetupObserver = null;

                            if (mBootCompleted) {
                                setUp();
                            }
                        }
                    }
                };
                cr.registerContentObserver(Secure.getUriFor(Secure.USER_SETUP_COMPLETE),
                        false /* notifyForDescendents */, mUserSetupObserver, mCurrentUser);
            } else if (mBootCompleted) {
                setUp();
            }
        }
    }

    private static boolean isUserSetupCompleted(ContentResolver cr, int userHandle) {
        return Secure.getIntForUser(cr, Secure.USER_SETUP_COMPLETE, 0, userHandle) == 1;
    }

    private void setUp() {
        Slog.d(TAG, "setUp: currentUser=" + mCurrentUser);

        // Create a new controller for the current user and start listening for changes.
        mController = new NightDisplayController(getContext(), mCurrentUser);
        mController.setListener(this);

        // Initialize the current auto mode.
        onAutoModeChanged(mController.getAutoMode());

        // Force the initialization current activated state.
        if (mIsActivated == null) {
            onActivated(mController.isActivated());
        }

        // Transition the screen to the current temperature.
        applyTint(false);
    }

    private void tearDown() {
        Slog.d(TAG, "tearDown: currentUser=" + mCurrentUser);

        if (mController != null) {
            mController.setListener(null);
            mController = null;
        }

        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        mIsActivated = null;
    }

    @Override
    public void onActivated(boolean activated) {
        if (mIsActivated == null || mIsActivated != activated) {
            Slog.i(TAG, activated ? "Turning on night display" : "Turning off night display");

            mIsActivated = activated;

            if (mAutoMode != null) {
                mAutoMode.onActivated(activated);
            }

            applyTint(false);
        }
    }

    @Override
    public void onAutoModeChanged(int autoMode) {
        Slog.d(TAG, "onAutoModeChanged: autoMode=" + autoMode);

        if (mAutoMode != null) {
            mAutoMode.onStop();
            mAutoMode = null;
        }

        if (autoMode == NightDisplayController.AUTO_MODE_CUSTOM) {
            mAutoMode = new CustomAutoMode();
        } else if (autoMode == NightDisplayController.AUTO_MODE_TWILIGHT) {
            mAutoMode = new TwilightAutoMode();
        }

        if (mAutoMode != null) {
            mAutoMode.onStart();
        }
    }

    @Override
    public void onCustomStartTimeChanged(LocalTime startTime) {
        Slog.d(TAG, "onCustomStartTimeChanged: startTime=" + startTime);

        if (mAutoMode != null) {
            mAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    @Override
    public void onCustomEndTimeChanged(LocalTime endTime) {
        Slog.d(TAG, "onCustomEndTimeChanged: endTime=" + endTime);

        if (mAutoMode != null) {
            mAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    @Override
    public void onColorTemperatureChanged(int colorTemperature) {
        applyTint(true);
    }

    @Override
    public void onDisplayColorModeChanged(int colorMode) {
        if (mController.isActivated()) {
            applyTint(true);
        }
    }

    /**
     * Applies current color temperature matrix, or removes it if deactivated.
     *
     * @param immediate {@code true} skips transition animation
     */
    private void applyTint(boolean immediate) {
        // Don't do any color matrix change animations if we are ignoring them anyway.
        if (mIgnoreAllColorMatrixChanges.get()) {
            return;
        }

        setMdnieTemp(mController.isActivated());
    }

    private void setMdnieTemp(boolean on) {
        // /sys/class/mdnie/mdnie/mdnie_temp
        //
        // TEMP_STANDARD =0,
        // TEMP_WARM,
        // TEMP_COLD,
        // MAX_TEMP_MODE,
        
        try {
            FileOutputStream fos = new FileOutputStream(TEMPERATURE_FILE);
            byte[] bytes = new byte[2];
            bytes[0] = (byte)(on ? '1' : '0');
            bytes[1] = '\n';
            fos.write(bytes);
            fos.close();
        } catch (FileNotFoundException fnfex) {
            Slog.e(TAG, "", fnfex);
        } catch (IOException ioex) {
            Slog.e(TAG, "", ioex);
        }
    }

    /**
     * Returns the first date time corresponding to the local time that occurs before the
     * provided date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the prior LocalDateTime corresponding to this local time
     */
    public static LocalDateTime getDateTimeBefore(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time yesterday.
        return ldt.isAfter(compareTime) ? ldt.minusDays(1) : ldt;
    }

    /**
     * Returns the first date time corresponding to this local time that occurs after the
     * provided date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the next LocalDateTime corresponding to this local time
     */
    public static LocalDateTime getDateTimeAfter(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time tomorrow.
        return ldt.isBefore(compareTime) ? ldt.plusDays(1) : ldt;
    }

    private abstract class AutoMode implements NightDisplayController.Callback {
        public abstract void onStart();

        public abstract void onStop();
    }

    private class CustomAutoMode extends AutoMode implements AlarmManager.OnAlarmListener {

        private final AlarmManager mAlarmManager;
        private final BroadcastReceiver mTimeChangedReceiver;

        private LocalTime mStartTime;
        private LocalTime mEndTime;

        private LocalDateTime mLastActivatedTime;

        CustomAutoMode() {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActivated();
                }
            };
        }

        private void updateActivated() {
            final LocalDateTime now = LocalDateTime.now();
            final LocalDateTime start = getDateTimeBefore(mStartTime, now);
            final LocalDateTime end = getDateTimeAfter(mEndTime, start);
            boolean activate = now.isBefore(end);

            if (mLastActivatedTime != null) {
                // Maintain the existing activated state if within the current period.
                if (mLastActivatedTime.isBefore(now) && mLastActivatedTime.isAfter(start)
                        && (mLastActivatedTime.isAfter(end) || now.isBefore(end))) {
                    activate = mController.isActivated();
                }
            }

            if (mIsActivated == null || mIsActivated != activate) {
                mController.setActivated(activate);
            }
            updateNextAlarm(mIsActivated, now);
        }

        private void updateNextAlarm(@Nullable Boolean activated, @NonNull LocalDateTime now) {
            if (activated != null) {
                final LocalDateTime next = activated ? getDateTimeAfter(mEndTime, now)
                        : getDateTimeAfter(mStartTime, now);
                final long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                mAlarmManager.setExact(AlarmManager.RTC, millis, TAG, this, null);
            }
        }

        @Override
        public void onStart() {
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);

            mStartTime = mController.getCustomStartTime();
            mEndTime = mController.getCustomEndTime();

            mLastActivatedTime = mController.getLastActivatedTime();

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            getContext().unregisterReceiver(mTimeChangedReceiver);

            mAlarmManager.cancel(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = mController.getLastActivatedTime();
            updateNextAlarm(activated, LocalDateTime.now());
        }

        @Override
        public void onCustomStartTimeChanged(LocalTime startTime) {
            mStartTime = startTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onCustomEndTimeChanged(LocalTime endTime) {
            mEndTime = endTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onAlarm() {
            Slog.d(TAG, "onAlarm");
            updateActivated();
        }
    }

    private class TwilightAutoMode extends AutoMode implements TwilightListener {

        private final TwilightManager mTwilightManager;

        TwilightAutoMode() {
            mTwilightManager = getLocalService(TwilightManager.class);
        }

        private void updateActivated(TwilightState state) {
            if (state == null) {
                // If there isn't a valid TwilightState then just keep the current activated
                // state.
                return;
            }

            boolean activate = state.isNight();
            final LocalDateTime lastActivatedTime = mController.getLastActivatedTime();
            if (lastActivatedTime != null) {
                final LocalDateTime now = LocalDateTime.now();
                final LocalDateTime sunrise = state.sunrise();
                final LocalDateTime sunset = state.sunset();

                // Maintain the existing activated state if within the current period.
                if (lastActivatedTime.isBefore(now) && (lastActivatedTime.isBefore(sunrise)
                        ^ lastActivatedTime.isBefore(sunset))) {
                    activate = mController.isActivated();
                }
            }

            if (mIsActivated == null || mIsActivated != activate) {
                mController.setActivated(activate);
            }
        }

        @Override
        public void onStart() {
            mTwilightManager.registerListener(this, mHandler);

            // Force an update to initialize state.
            updateActivated(mTwilightManager.getLastTwilightState());
        }

        @Override
        public void onStop() {
            mTwilightManager.unregisterListener(this);
        }

        @Override
        public void onActivated(boolean activated) {
        }

        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            Slog.d(TAG, "onTwilightStateChanged: isNight="
                    + (state == null ? null : state.isNight()));
            updateActivated(state);
        }
    }
}
