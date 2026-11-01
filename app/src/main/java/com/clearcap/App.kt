package com.clearcap

import android.app.Application
import android.content.Context
import com.clearcap.data.SettingsRepository
import com.clearcap.data.TranscriptRepository
import com.clearcap.data.db.AppDatabase
import com.clearcap.models.ModelManager
import com.clearcap.session.RecoverySweep
import com.clearcap.session.SessionBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Plain service locator. No analytics, ads, accounts or crash reporters. */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: SettingsRepository private set
    lateinit var models: ModelManager private set
    lateinit var repo: TranscriptRepository private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this, appScope)
        models = ModelManager(this)
        repo = TranscriptRepository(this, AppDatabase.create(this))
        appScope.launch {
            runCatching { repo.importLegacy() }
            if (!SessionBus.state.value.busy) runCatching { RecoverySweep.run(repo) }
            runCatching { repo.deleteOrphanAudio() }
            runCatching { repo.purgeOlderThan(settings.current().transcriptRetentionDays) }
        }
    }
}

val Context.app: App get() = applicationContext as App
