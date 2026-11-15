package com.clearcap.pipeline

import com.clearcap.models.DeviceInfo
import com.clearcap.models.DeviceProfile
import com.clearcap.models.ModelCatalog
import com.clearcap.transcript.LanguageMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    private val flagship = DeviceInfo(8000, 4000, 50_000, 8, listOf("arm64-v8a"), 34)
    private val lowEnd = DeviceInfo(2000, 700, 3000, 4, listOf("armeabi-v7a"), 29)

    @Test fun englishUsesStreamingWhenInstalled() {
        val r = EngineRouter.route(LanguageMode.ENGLISH, setOf("zipformer-en"), null, flagship)
        assertEquals(LiveMode.STREAMING, r.mode)
        assertTrue(r.missing.isEmpty())
    }

    @Test fun englishWithoutAnyModelAsksForTheLiveModel() {
        val r = EngineRouter.route(LanguageMode.ENGLISH, emptySet(), null, flagship)
        assertEquals(LiveMode.NONE, r.mode)
        assertEquals(listOf("zipformer-en"), r.missing.map { it.id })
    }

    @Test fun englishFallsBackToNearLiveWhisper() {
        val r = EngineRouter.route(LanguageMode.ENGLISH, setOf("whisper-base", "silero-vad"), null, flagship)
        assertEquals(LiveMode.NEAR_LIVE, r.mode)
        assertEquals("whisper-base", r.liveModel?.id)
    }

    @Test fun hindiNeedsWhisperAndVad() {
        val r = EngineRouter.route(LanguageMode.HINDI, setOf("zipformer-en"), null, flagship)
        assertEquals(LiveMode.NONE, r.mode)
        assertTrue(r.missing.any { it.id == "silero-vad" })
        assertTrue(r.missing.any { it.family.name == "WHISPER" })
    }

    @Test fun hinglishIsNearLiveNeverStreaming() {
        val r = EngineRouter.route(LanguageMode.HINGLISH, setOf("zipformer-en", "whisper-small", "silero-vad"), null, flagship)
        assertEquals(LiveMode.NEAR_LIVE, r.mode)
        assertEquals("whisper-small", r.liveModel?.id)
    }

    @Test fun preferredWhisperIsHonouredWhenInstalled() {
        val r = EngineRouter.route(LanguageMode.AUTO, setOf("whisper-base", "whisper-small", "silero-vad"), "whisper-base", flagship)
        assertEquals("whisper-base", r.liveModel?.id)
    }

    @Test fun recommendationDependsOnDevice() {
        assertEquals("whisper-small", DeviceProfile.recommendedWhisper(flagship).id)
        assertEquals("whisper-tiny", DeviceProfile.recommendedWhisper(lowEnd).id)
    }

    @Test fun refinementSkippedWhenDisabledOrNoAudio() {
        val inst = setOf("zipformer-en", "parakeet-110m", "silero-vad")
        val route = EngineRouter.route(LanguageMode.ENGLISH, inst, null, flagship)
        assertFalse(RefinementPlanner.plan(false, LanguageMode.ENGLISH, route, inst, true, null, flagship).run)
        assertFalse(RefinementPlanner.plan(true, LanguageMode.ENGLISH, route, inst, false, null, flagship).run)
        val p = RefinementPlanner.plan(true, LanguageMode.ENGLISH, route, inst, true, null, flagship)
        assertTrue(p.run)
        assertEquals("parakeet-110m", p.model?.id)
    }

    @Test fun refinementNeverUsesAWeakerOrSameModel() {
        val inst = setOf("whisper-base", "whisper-tiny", "silero-vad")
        val route = EngineRouter.route(LanguageMode.HINDI, inst, "whisper-base", flagship)
        val p = RefinementPlanner.plan(true, LanguageMode.HINDI, route, inst, true, null, flagship)
        assertFalse(p.run)
        assertNull(p.model)
    }

    @Test fun refinementForHindiUsesLargerWhisper() {
        val inst = setOf("whisper-base", "whisper-small", "silero-vad")
        val route = EngineRouter.route(LanguageMode.HINDI, inst, "whisper-base", flagship)
        val p = RefinementPlanner.plan(true, LanguageMode.HINDI, route, inst, true, null, flagship)
        assertTrue(p.run)
        assertEquals("whisper-small", p.model?.id)
    }

    @Test fun parakeetIsNeverUsedForHindi() {
        val inst = setOf("whisper-tiny", "parakeet-110m", "silero-vad")
        val route = EngineRouter.route(LanguageMode.HINDI, inst, null, flagship)
        val p = RefinementPlanner.plan(true, LanguageMode.HINDI, route, inst, true, null, flagship)
        assertFalse(p.model?.id == "parakeet-110m")
    }

    @Test fun catalogIsConsistent() {
        for (m in ModelCatalog.all) {
            assertEquals(64, m.archiveSha256.length)
            assertTrue(m.files.isNotEmpty())
            assertTrue(m.url.startsWith("https://"))
            assertTrue(m.archiveBytes > 0)
        }
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
    }
}
