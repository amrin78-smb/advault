package com.advault.intelligence

import android.content.pm.ApplicationInfo
import com.advault.intelligence.apps.GameDetector
import com.advault.intelligence.apps.GameSignal
import com.advault.intelligence.apps.SdkDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkAndGameDetectorTest {
    private val providers = TestData.bundled().providers

    @Test fun detectsSdksFromComponentsAndMetaData() {
        val components = listOf(
            "com.example.game.MainActivity",
            "com.google.android.gms.ads.AdActivity",
            "com.unity3d.services.ads.adunit.AdUnitActivity",
            "com.applovin.adview.AppLovinFullscreenActivity",
            "com.ironsource.sdk.controller.ControllerActivity",
            "com.facebook.ads.AudienceNetworkActivity",
            "com.vungle.ads.internal.ui.VungleActivity",
        )
        val ids = SdkDetector.detect(components, listOf("applovin.sdk.key"), providers)
        for (id in listOf("google_admob", "unity_ads", "applovin", "ironsource", "meta_audience_network", "liftoff_vungle")) {
            assertTrue("$id not detected in $ids", id in ids)
        }
        assertFalse("google_ad_manager" in ids)
    }

    @Test fun metaDataOnlyDetection() {
        assertEquals(listOf("google_admob"), SdkDetector.detect(emptyList(), listOf("com.google.android.gms.ads.APPLICATION_ID"), providers))
        assertEquals(listOf("google_ad_manager"), SdkDetector.detect(emptyList(), listOf("com.google.android.gms.ads.AD_MANAGER_APP"), providers))
        assertEquals(listOf("applovin"), SdkDetector.detect(emptyList(), listOf("applovin.sdk.key"), providers))
        val firebase = SdkDetector.detect(emptyList(),
            listOf("com.google.firebase.components:com.google.firebase.crashlytics.CrashlyticsRegistrar"), providers)
        assertEquals(listOf("firebase_crashlytics"), firebase)
    }

    @Test fun noFalsePositives() {
        val ids = SdkDetector.detect(
            listOf("com.example.MainActivity", "com.facebook.FacebookActivity", "com.vunglex.Foo",
                "com.google.android.gms.auth.api.signin.internal.SignInHubActivity", "com.google.firebase.provider.FirebaseInitProvider"),
            listOf("com.facebook.sdk.ApplicationId", "com.google.android.gms.version"),
            providers,
        )
        assertTrue(ids.toString(), ids.isEmpty())
    }

    @Test fun prefixBoundaries() {
        assertTrue(SdkDetector.matchesPrefix("com.vungle.ads.X", "com.vungle"))
        assertTrue(SdkDetector.matchesPrefix("com.vungle", "com.vungle"))
        assertTrue(SdkDetector.matchesPrefix("a.B\$Inner", "a.B"))
        assertFalse(SdkDetector.matchesPrefix("com.vunglex.Y", "com.vungle"))
        assertTrue(SdkDetector.matchesPrefix("com.unity3d.ads.X", "com.unity3d.ads."))
        assertFalse(SdkDetector.matchesPrefix("x", ""))
    }

    @Test fun gameDetection() {
        val none = ApplicationInfo.CATEGORY_UNDEFINED
        assertEquals(GameSignal.CATEGORY_GAME, GameDetector.detect(ApplicationInfo.CATEGORY_GAME, 0, emptyList(), emptyList()).signal)
        @Suppress("DEPRECATION")
        assertEquals(GameSignal.LEGACY_GAME_FLAG, GameDetector.detect(none, ApplicationInfo.FLAG_IS_GAME, emptyList(), emptyList()).signal)
        val unity = GameDetector.detect(none, 0, listOf("com.unity3d.player.UnityPlayerActivity"), emptyList())
        assertTrue(unity.isGame); assertEquals("Unity", unity.engine); assertTrue(unity.signal!!.heuristic)
        assertEquals("Unreal Engine", GameDetector.detect(none, 0, listOf("com.epicgames.unreal.GameActivity"), emptyList()).engine)
        assertEquals("Cocos2d-x", GameDetector.detect(none, 0, listOf("org.cocos2dx.lib.Cocos2dxActivity"), emptyList()).engine)
        assertEquals("Godot", GameDetector.detect(none, 0, listOf("com.godot.game.GodotApp"), emptyList()).engine)
        val pg = GameDetector.detect(none, 0, emptyList(), listOf(GameDetector.PLAY_GAMES_APP_ID))
        assertEquals(GameSignal.PLAY_GAMES_HEURISTIC, pg.signal)
        val app = GameDetector.detect(ApplicationInfo.CATEGORY_PRODUCTIVITY, 0, listOf("com.example.MainActivity"), emptyList())
        assertFalse(app.isGame)
        assertFalse(GameSignal.CATEGORY_GAME.heuristic)
    }
}
