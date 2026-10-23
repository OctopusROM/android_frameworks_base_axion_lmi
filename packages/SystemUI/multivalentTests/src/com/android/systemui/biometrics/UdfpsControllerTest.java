/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_VENDOR;

import static com.android.systemui.SysuiTestCaseExtKt.testKosmos;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.hardware.biometrics.BiometricRequestConstants;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor;
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams;
import com.android.systemui.biometrics.udfps.SinglePointerTouchProcessor;
import com.android.systemui.biometrics.ui.viewmodel.DefaultUdfpsTouchOverlayViewModel;
import com.android.systemui.biometrics.ui.viewmodel.DeviceEntryUdfpsTouchOverlayViewModel;
import com.android.systemui.biometrics.ui.viewmodel.PromptUdfpsTouchOverlayViewModel;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.camera.CameraGestureHelper;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.UserActivityNotifierKosmosKt;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.kosmos.Kosmos;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.data.repository.FakePowerRepository;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.power.shared.model.WakeSleepReason;
import com.android.systemui.power.shared.model.WakefulnessState;
import com.android.systemui.settings.brightness.domain.interactor.BrightnessMirrorShowingInteractor;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.FakeExecution;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.time.SystemClock;

import com.google.android.msdl.domain.MSDLPlayer;

import dagger.Lazy;

import kotlinx.coroutines.CoroutineScope;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
public class UdfpsControllerTest extends SysuiTestCase {
    private final Kosmos mKosmos = testKosmos(this);
    private static final long TEST_REQUEST_ID = 70;
    private String mScreenOffUnlockSetting;

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    // Unit under test
    private UdfpsController mUdfpsController;
    // Dependencies
    private FakeExecutor mBiometricExecutor;
    @Mock
    private LayoutInflater mLayoutInflater;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private IUdfpsOverlayControllerCallback mUdfpsOverlayControllerCallback;
    @Mock
    private FalsingManager mFalsingManager;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private ScreenLifecycle mScreenLifecycle;
    @Mock
    private VibratorHelper mVibrator;
    @Mock
    private UdfpsHapticsSimulator mUdfpsHapticsSimulator;
    @Mock
    private UdfpsShell mUdfpsShell;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private DisplayManager mDisplayManager;
    @Mock
    private Handler mHandler;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private SystemClock mSystemClock;
    @Mock
    private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock
    private LatencyTracker mLatencyTracker;
    private FakeExecutor mFgExecutor;
    @Mock
    private UdfpsDisplayMode mUdfpsDisplayMode;
    @Mock
    private SystemUIDialogManager mSystemUIDialogManager;
    @Mock
    private ActivityTransitionAnimator mActivityTransitionAnimator;
    @Mock
    private PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @Mock
    private ShadeInteractor mShadeInteractor;
    @Mock
    private SinglePointerTouchProcessor mSinglePointerTouchProcessor;
    @Mock
    private SessionTracker mSessionTracker;
    @Mock
    private AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock
    private UdfpsOverlayInteractor mUdfpsOverlayInteractor;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private Lazy<WakefulnessLifecycle> mWakefulnessLifecycle;

    // Capture listeners so that they can be used to send events
    @Captor
    private ArgumentCaptor<IUdfpsOverlayController> mOverlayCaptor;
    private IUdfpsOverlayController mOverlayController;
    @Captor
    private ArgumentCaptor<ScreenLifecycle.Observer> mScreenObserverCaptor;
    private FingerprintSensorPropertiesInternal mOpticalProps;
    private PowerInteractor mPowerInteractor;
    private FakePowerRepository mPowerRepository;
    @Mock
    private InputManager mInputManager;
    @Mock
    private KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    @Mock
    private Lazy<DeviceEntryUdfpsTouchOverlayViewModel> mDeviceEntryUdfpsTouchOverlayViewModel;
    @Mock
    private Lazy<DefaultUdfpsTouchOverlayViewModel> mDefaultUdfpsTouchOverlayViewModel;
    @Mock
    private Lazy<PromptUdfpsTouchOverlayViewModel> mPromptUdfpsTouchOverlayViewModel;
    @Mock
    private Provider<CameraGestureHelper> mCameraGestureHelper;

    @Before
    public void setUp() {
        mScreenOffUnlockSetting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED, 0);
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED, null, 0);
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.bool.config_screen_off_udfps_enabled, true);
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.bool.config_screen_off_udfps_default_on, true);
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.systemui.res.R.integer.config_udfpsVendorCode, 22);

        mPowerRepository = new FakePowerRepository();
        mPowerInteractor = new PowerInteractor(
                mPowerRepository,
                mock(FalsingCollector.class),
                mock(ScreenOffAnimationController.class),
                mStatusBarStateController,
                mCameraGestureHelper
        );
        mPowerRepository.updateWakefulness(
                WakefulnessState.AWAKE,
                WakeSleepReason.POWER_BUTTON,
                WakeSleepReason.OTHER,
                /* powerButtonLaunchGestureTriggered */ false
        );
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.bool.config_ignoreUdfpsVote, false);

        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        when(mSessionTracker.getSessionId(anyInt())).thenReturn(
                (new InstanceIdSequence(1 << 20)).newInstanceId());

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        mOpticalProps = new FingerprintSensorPropertiesInternal(1 /* sensorId */,
                SensorProperties.STRENGTH_STRONG,
                5 /* maxEnrollmentsPerUser */,
                componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                true /* resetLockoutRequiresHardwareAuthToken */);

        mFgExecutor = new FakeExecutor(new FakeSystemClock());

        // Create a fake background executor.
        mBiometricExecutor = new FakeExecutor(new FakeSystemClock());

        initUdfpsController(mOpticalProps);
    }

    private void initUdfpsController(FingerprintSensorPropertiesInternal sensorProps) {
        reset(mFingerprintManager);
        reset(mScreenLifecycle);

        mUdfpsController = new UdfpsController(
                mContext,
                new FakeExecution(),
                mLayoutInflater,
                mFingerprintManager,
                mWindowManager,
                mStatusBarStateController,
                mFgExecutor,
                mStatusBarKeyguardViewManager,
                mDumpManager,
                mKeyguardUpdateMonitor,
                mFalsingManager,
                mPowerManager,
                mAccessibilityManager,
                mScreenLifecycle,
                mVibrator,
                mUdfpsHapticsSimulator,
                mUdfpsShell,
                mKeyguardStateController,
                mDisplayManager,
                mHandler,
                mConfigurationController,
                mSystemClock,
                mUnlockedScreenOffAnimationController,
                mSystemUIDialogManager,
                mLatencyTracker,
                mActivityTransitionAnimator,
                mBiometricExecutor,
                mPrimaryBouncerInteractor,
                mShadeInteractor,
                mSinglePointerTouchProcessor,
                mSessionTracker,
                mAlternateBouncerInteractor,
                mInputManager,
                mock(DeviceEntryFaceAuthInteractor.class),
                mSelectedUserInteractor,
                mKeyguardTransitionInteractor,
                mDeviceEntryUdfpsTouchOverlayViewModel,
                mDefaultUdfpsTouchOverlayViewModel,
                mPromptUdfpsTouchOverlayViewModel,
                mUdfpsOverlayInteractor,
                mPowerInteractor,
                mock(BrightnessMirrorShowingInteractor.class),
                mock(CoroutineScope.class),
                UserActivityNotifierKosmosKt.getUserActivityNotifier(mKosmos),
                mWakefulnessLifecycle,
                mock(MSDLPlayer.class),
                mock(AuthController.class)
        );
        verify(mFingerprintManager).setUdfpsOverlayController(mOverlayCaptor.capture());
        mOverlayController = mOverlayCaptor.getValue();
        verify(mScreenLifecycle).addObserver(mScreenObserverCaptor.capture());

        mUdfpsController.updateOverlayParams(sensorProps, new UdfpsOverlayParams());
        mUdfpsController.setUdfpsDisplayMode(mUdfpsDisplayMode);
    }

    @After
    public void restoreScreenOffUnlockSetting() {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED, mScreenOffUnlockSetting, 0);
    }

    private void prepareScreenOffOverlay() {
        final UdfpsControllerOverlay overlay = mock(UdfpsControllerOverlay.class);
        when(overlay.getRequestId()).thenReturn(TEST_REQUEST_ID);
        when(overlay.matchesRequestId(TEST_REQUEST_ID)).thenReturn(true);
        when(overlay.getRequestReason()).thenReturn(
                BiometricRequestConstants.REASON_AUTH_KEYGUARD);
        mUdfpsController.mOverlay = overlay;
        mUdfpsController.mOverlayParams = new UdfpsOverlayParams(
                new Rect(439, 1655, 641, 1857), new Rect(), 1080, 2400, 1f,
                Surface.ROTATION_0, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL);
    }

    @Test
    public void onAcquired_wakesAndScans_whenScreenOffUnlockDefaultsOn() throws RemoteException {
        // GIVEN a black screen with an active keyguard request and no saved preference.
        prepareScreenOffOverlay();

        // WHEN Goodix reports a finger on the sensor.
        mOverlayController.onAcquired(mOpticalProps.sensorId, FINGERPRINT_ACQUIRED_VENDOR, 22);
        mFgExecutor.runAllReady();

        // THEN the device wakes and the real AOD path starts a scan at the sensor center.
        verify(mPowerManager).wakeUp(anyLong(), eq(PowerManager.WAKE_REASON_GESTURE), anyString());
        verify(mFingerprintManager).onPointerDown(eq(TEST_REQUEST_ID), eq(mOpticalProps.sensorId),
                anyInt(), eq(540f), eq(1756f), anyFloat(), anyFloat(), anyFloat(),
                anyLong(), anyLong(), eq(true));
        verify(mFingerprintManager, never()).onPointerUp(anyLong(), anyInt(), anyInt(),
                anyFloat(), anyFloat(), anyFloat(), anyFloat(), anyFloat(),
                anyLong(), anyLong(), anyBoolean());
    }

    @Test
    public void onAcquired_doesNotWake_whenScreenOffUnlockDisabled() throws RemoteException {
        // GIVEN the user explicitly disabled screen-off fingerprint.
        prepareScreenOffOverlay();
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.SCREEN_OFF_UNLOCK_UDFPS_ENABLED, 0, 0);

        // WHEN Goodix reports a finger on the sensor.
        mOverlayController.onAcquired(mOpticalProps.sensorId, FINGERPRINT_ACQUIRED_VENDOR, 22);
        mFgExecutor.runAllReady();

        // THEN the saved preference takes precedence over the enabled device default.
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void onAcquired_doesNotWake_whenDeviceDoesNotSupportScreenOffUnlock()
            throws RemoteException {
        // GIVEN screen-off fingerprint is unavailable on this device.
        prepareScreenOffOverlay();
        mContext.getOrCreateTestableResources()
                .addOverride(com.android.internal.R.bool.config_screen_off_udfps_enabled, false);

        // WHEN the matching vendor code arrives.
        mOverlayController.onAcquired(mOpticalProps.sensorId, FINGERPRINT_ACQUIRED_VENDOR, 22);
        mFgExecutor.runAllReady();

        // THEN the device remains asleep.
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void onAcquired_doesNotWake_whenVendorCodeIsNotFingerPress() throws RemoteException {
        // GIVEN an active screen-off keyguard request.
        prepareScreenOffOverlay();

        // WHEN Goodix reports waiting for authentication rather than a press.
        mOverlayController.onAcquired(mOpticalProps.sensorId, FINGERPRINT_ACQUIRED_VENDOR, 21);
        mFgExecutor.runAllReady();

        // THEN the device remains asleep.
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void onAcquired_doesNotWake_whenFingerprintDetectionStopped() throws RemoteException {
        // GIVEN keyguard is no longer listening for fingerprints.
        prepareScreenOffOverlay();
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(false);

        // WHEN a late finger-press event arrives.
        mOverlayController.onAcquired(mOpticalProps.sensorId, FINGERPRINT_ACQUIRED_VENDOR, 22);
        mFgExecutor.runAllReady();

        // THEN stale events do not wake the device.
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void onAcquired_doesNotWake_whenOverlayIsForEnrollment() throws RemoteException {
        // GIVEN the overlay belongs to enrollment rather than keyguard.
        prepareScreenOffOverlay();
        when(mUdfpsController.mOverlay.getRequestReason()).thenReturn(
                BiometricRequestConstants.REASON_ENROLL_ENROLLING);

        // WHEN a vendor finger-press event arrives.
        mOverlayController.onAcquired(mOpticalProps.sensorId, FINGERPRINT_ACQUIRED_VENDOR, 22);
        mFgExecutor.runAllReady();

        // THEN non-keyguard requests cannot wake the device.
        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void hideUdfpsOverlay_resetsAltAuthBouncerWhenShowing() throws RemoteException {
        // GIVEN overlay was showing and the udfps bouncer is showing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // WHEN the overlay is hidden
        mOverlayController.hideUdfpsOverlay(mOpticalProps.sensorId);
        mFgExecutor.runAllReady();

        // THEN the udfps bouncer is reset
        verify(mStatusBarKeyguardViewManager).hideAlternateBouncer(eq(true));
    }

    @Test
    public void showUdfpsOverlay_callsListener() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mFingerprintManager).onUdfpsUiEvent(FingerprintManager.UDFPS_UI_OVERLAY_SHOWN,
                TEST_REQUEST_ID, mOpticalProps.sensorId);
    }

    @Test
    public void showUdfpsOverlay_invokedTwice_doesNotNotifyListenerSecondTime() throws RemoteException {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mFingerprintManager).onUdfpsUiEvent(FingerprintManager.UDFPS_UI_OVERLAY_SHOWN,
                TEST_REQUEST_ID, mOpticalProps.sensorId);

        reset(mFingerprintManager);

        // Second attempt should do nothing
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();
        verify(mFingerprintManager, never()).onUdfpsUiEvent(FingerprintManager.UDFPS_UI_OVERLAY_SHOWN,
                TEST_REQUEST_ID, mOpticalProps.sensorId);
    }

    @Test
    public void testSubscribesToOrientationChangesWhenShowingOverlay() throws Exception {
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_AUTH_KEYGUARD, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).registerDisplayListener(any(), eq(mHandler), anyLong());

        mOverlayController.hideUdfpsOverlay(mOpticalProps.sensorId);
        mFgExecutor.runAllReady();

        verify(mDisplayManager).unregisterDisplayListener(any());
    }

    @Test
    public void updateOverlayParams_doesNothing_ifParamsDidntChange() throws Exception {
        final Rect sensorBounds = new Rect(10, 10, 20, 20);
        final int displayWidth = 1080;
        final int displayHeight = 1920;
        final float scaleFactor = 1f;
        final int rotation = Surface.ROTATION_0;

        // Initialize the overlay.
        mUdfpsController.updateOverlayParams(mOpticalProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, rotation, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL));

        // Show the overlay.
        mOverlayController.showUdfpsOverlay(TEST_REQUEST_ID, mOpticalProps.sensorId,
                BiometricRequestConstants.REASON_ENROLL_ENROLLING, mUdfpsOverlayControllerCallback);
        mFgExecutor.runAllReady();

        // Update overlay with the same parameters.
        mUdfpsController.updateOverlayParams(mOpticalProps,
                new UdfpsOverlayParams(sensorBounds, sensorBounds, displayWidth, displayHeight,
                        scaleFactor, rotation, FingerprintSensorProperties.TYPE_UDFPS_OPTICAL));
        mFgExecutor.runAllReady();

        // Ensure the overlay was not recreated.
        verify(mWindowManager, never()).removeView(any());
    }
}
