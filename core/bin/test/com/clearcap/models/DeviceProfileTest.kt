package com.clearcap.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    private fun dev(total: Int, avail: Int = total / 2, storage: Long = 20_000, abis: List<String> = listOf("arm64-v8a")) =
        DeviceInfo(total, avail, storage, 8, abis, 33)

    @Test fun tiers() {
        assertEquals(DeviceTier.LOW, DeviceProfile.tier(1900))
        assertEquals(DeviceTier.MID, DeviceProfile.tier(3700))
        assertEquals(DeviceTier.HIGH, DeviceProfile.tier(7500))
    }

    @Test fun x86DevicesAreRejectedHonestly() {
        val f = DeviceProfile.fit(ModelCatalog.WHISPER_TINY, dev(8000, abis = listOf("x86_64")))
        assertEquals(FitLevel.BLOCK, f.level)
        assertTrue(f.message.contains("not supported"))
    }

    @Test fun smallModelBlockedOnTwoGigabytePhone() {
        assertEquals(FitLevel.BLOCK, DeviceProfile.fit(ModelCatalog.WHISPER_SMALL, dev(2000)).level)
    }

    @Test fun smallModelWarnsNearTheLimit() {
        assertEquals(FitLevel.WARN, DeviceProfile.fit(ModelCatalog.WHISPER_SMALL, dev(3500)).level)
    }

    @Test fun lowFreeMemoryWarns() {
        assertEquals(FitLevel.WARN, DeviceProfile.fit(ModelCatalog.WHISPER_BASE, dev(6000, avail = 200)).level)
    }

    @Test fun plentyOfMemoryIsOk() {
        assertEquals(FitLevel.OK, DeviceProfile.fit(ModelCatalog.WHISPER_SMALL, dev(8000, avail = 4000)).level)
    }

    @Test fun storageCheckUsesArchivePlusUnpacked() {
        val m = ModelCatalog.WHISPER_SMALL
        assertTrue(DeviceProfile.peakStorageMb(m) > m.downloadMb + m.installedMb - 5)
        assertEquals(FitLevel.BLOCK, DeviceProfile.storageFit(m, dev(8000, storage = 500)).level)
        assertEquals(FitLevel.OK, DeviceProfile.storageFit(m, dev(8000, storage = 5000)).level)
    }

    @Test fun threadsLeaveACoreFree() {
        assertEquals(1, DeviceProfile.threads(1))
        assertEquals(3, DeviceProfile.threads(4))
        assertEquals(4, DeviceProfile.threads(16))
    }
}
