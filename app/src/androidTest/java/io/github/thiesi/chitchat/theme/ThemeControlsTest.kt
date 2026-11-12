package io.github.thiesi.chitchat.theme

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.core.settings.SettingsRepository
import io.github.thiesi.chitchat.core.theme.ChitChatTheme
import io.github.thiesi.chitchat.core.theme.Lantern
import io.github.thiesi.chitchat.core.theme.LanternOutlinedButton
import io.github.thiesi.chitchat.core.ui.PrimaryButton
import io.github.thiesi.chitchat.core.ui.CountBadge
import io.github.thiesi.chitchat.core.ui.AttentionBadge
import io.github.thiesi.chitchat.testing.assertMinTouchTarget
import io.github.thiesi.chitchat.testing.performNativeTap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
class ThemeControlsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun filledActionAndCountBadgeUseReadableLanternColorsAndRealTouchTargets() {
        if(Build.VERSION.SDK_INT>=34) compose.enableAccessibilityChecks()
        val mode=mutableIntStateOf(0);var clicks=0
        var labelColor=Lantern.light.text
        compose.setContent {
            ChitChatTheme(dark=mode.intValue!=0,pureBlack=mode.intValue==2) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    PrimaryButton(onClick={ clicks++ },modifier=Modifier.testTag("primary")) { Text("Continue") }
                    LanternOutlinedButton(onClick={},modifier=Modifier.testTag("outlined")) {
                        val color=androidx.compose.material3.LocalContentColor.current
                        SideEffect { labelColor=color };Text("Change server")
                    }
                    CountBadge(Modifier.testTag("count")) { Text("12") }
                }
            }
        }
        for(value in 0..2) {
            compose.runOnIdle { mode.intValue=value }
            compose.onNodeWithTag("primary").assertMinTouchTarget(compose.density).performNativeTap()
            compose.onNodeWithText("12").assertIsDisplayed()
            if(Build.VERSION.SDK_INT>=34) compose.onRoot().tryPerformAccessibilityChecks()
            if(Build.VERSION.SDK_INT>=28) {
                val pixels=compose.onNodeWithTag("count").captureToImage().toPixelMap()
                assertEquals(Lantern.light.signature.toArgb(),pixels[pixels.width/2,2].toArgb())
                val palette=listOf(Lantern.light,Lantern.dark,Lantern.amoledBlack)[value]
                val outlined=compose.onNodeWithTag("outlined").assertIsEnabled().assertMinTouchTarget(compose.density).captureToImage().toPixelMap()
                val border=(0 until outlined.height/3).map { outlined[outlined.width/2,it] }.first { it.toArgb()!=palette.ground.toArgb() }
                assertEquals(palette.fieldLine.toArgb(),border.toArgb())
                val a=border.luminance();val b=palette.ground.luminance()
                assertTrue((maxOf(a,b)+0.05)/(minOf(a,b)+0.05)>=3.0)
                compose.runOnIdle { assertEquals(palette.text,labelColor) }
            }
        }
        compose.runOnIdle { assertEquals(3,clicks) }
    }

    @Test fun attentionDotsKeepUncertaintyAndErrorsDistinctFromNeutralAttention() {
        if(Build.VERSION.SDK_INT>=34) compose.enableAccessibilityChecks()
        val mode=mutableIntStateOf(0);val status=mutableIntStateOf(0)
        compose.setContent {
            ChitChatTheme(dark=mode.intValue!=0,pureBlack=mode.intValue==2) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    AttentionBadge(Modifier.testTag("attention"),uncertain=(status.intValue and 1)!=0,failed=(status.intValue and 2)!=0)
                }
            }
        }
        for(theme in 0..2) for(kind in 0..3) {
            compose.runOnIdle { mode.intValue=theme;status.intValue=kind }
            if(Build.VERSION.SDK_INT>=34) compose.onRoot().tryPerformAccessibilityChecks()
            if(Build.VERSION.SDK_INT>=28) {
                val palette=listOf(Lantern.light,Lantern.dark,Lantern.amoledBlack)[theme]
                // States differ by shape (dot, help, error octagon), each drawn in its own color on ground (A-05).
                val expected=when(kind) { 1,3 -> palette.warning;2 -> palette.error;else -> palette.signatureText }
                val pixels=compose.onNodeWithTag("attention").captureToImage().toPixelMap()
                fun near(a: androidx.compose.ui.graphics.Color,b: androidx.compose.ui.graphics.Color)=
                    kotlin.math.abs(a.red-b.red)<0.04f && kotlin.math.abs(a.green-b.green)<0.04f && kotlin.math.abs(a.blue-b.blue)<0.04f
                // Thin antialiased strokes rarely hit the exact color: accept a pixel lying on the ground-to-color
                // blend, at least halfway towards the state color.
                val g=palette.ground
                fun strokeOf(p: androidx.compose.ui.graphics.Color): Boolean {
                    val dr=expected.red-g.red; val dg=expected.green-g.green; val db=expected.blue-g.blue
                    val t=((p.red-g.red)*dr+(p.green-g.green)*dg+(p.blue-g.blue)*db)/(dr*dr+dg*dg+db*db)
                    val rr=p.red-(g.red+t*dr); val rg=p.green-(g.green+t*dg); val rb=p.blue-(g.blue+t*db)
                    return t>=0.5f && rr*rr+rg*rg+rb*rb<0.0025f
                }
                val colors=(0 until pixels.width).flatMap { x -> (0 until pixels.height).map { y -> pixels[x,y] } }
                assertTrue("theme=$theme, status=$kind draws its own color",colors.any(::strokeOf))
                if(kind==0) assertTrue("generic is a filled dot",near(pixels[pixels.width/2,pixels.height/2],expected))
            }
        }
    }

    @Test fun pureBlackIsPersistedByTheRealRepositoryWithoutChangingThemeOrServer()=runBlocking {
        val store=SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val original=store.settings.first()
        try {
            store.savePureBlack(!original.pureBlack)
            val restored=SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext).settings.first()
            assertEquals(!original.pureBlack,restored.pureBlack)
            assertEquals(original.theme,restored.theme)
            assertEquals(original.server,restored.server)
        } finally { store.savePureBlack(original.pureBlack) }
    }
}
