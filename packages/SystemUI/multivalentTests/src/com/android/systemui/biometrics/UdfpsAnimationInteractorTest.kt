/*
 * Copyright (C) 2026 AxionOS
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

package com.android.systemui.biometrics

import android.graphics.Rect
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
class UdfpsAnimationInteractorTest : SysuiTestCase() {
    @JvmField @Rule var mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var deviceEntryUdfpsInteractor: DeviceEntryUdfpsInteractor
    @Mock private lateinit var udfpsOverlayInteractor: UdfpsOverlayInteractor

    @Test
    fun animationWindow_isCenteredOnSensor() =
        runTest {
            // Given
            context.orCreateTestableResources.addOverride(R.dimen.udfps_animation_size, 180)
            context.orCreateTestableResources.addOverride(R.dimen.udfps_animation_offset, 0)
            whenever(deviceEntryUdfpsInteractor.isListeningForUdfps)
                .thenReturn(MutableStateFlow(false))
            whenever(udfpsOverlayInteractor.isFingerDown).thenReturn(MutableStateFlow(false))
            val overlayParams = MutableStateFlow(UdfpsOverlayParams())
            whenever(udfpsOverlayInteractor.udfpsOverlayParams).thenReturn(overlayParams)
            val underTest =
                UdfpsAnimationInteractor(
                    scope = this,
                    context = context,
                    deviceEntryUdfpsInteractor = deviceEntryUdfpsInteractor,
                    udfpsOverlayInteractor = udfpsOverlayInteractor,
                )

            // When
            overlayParams.value = lmiOverlayParams
            runCurrent()

            // Then
            assertThat(underTest.uiState.value.animationOffsetY).isEqualTo(1666)
            assertThat(
                    underTest.uiState.value.animationOffsetY +
                        underTest.uiState.value.animationSize / 2
                )
                .isEqualTo(lmiOverlayParams.sensorBounds.centerY())
        }
}

private val lmiOverlayParams =
    UdfpsOverlayParams(
        sensorBounds = Rect(439, 1655, 641, 1857),
        overlayBounds = Rect(439, 1655, 641, 1857),
        naturalDisplayWidth = 1080,
        naturalDisplayHeight = 2400,
        scaleFactor = 1f,
        rotation = Surface.ROTATION_0,
    )
