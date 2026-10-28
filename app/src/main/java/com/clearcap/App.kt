package com.clearcap

import android.app.Application
import android.content.Context
import com.clearcap.data.ApiKeyStore
import com.clearcap.data.DictionaryStore
import com.clearcap.data.SettingsStore
import com.clearcap.data.TranscriptStore
import com.clearcap.engine.ModelManager

/** Tiny service locator. No analytics, ads, accounts or crash reporters. */
class App : Application() {
    lateinit var settings: SettingsStore private set
    lateinit var models: ModelManager private set
    lateinit var store: TranscriptStore private set
    lateinit var dictionary: DictionaryStore private set
    lateinit var keys: ApiKeyStore private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        models = ModelManager(this)
        store = TranscriptStore(this)
        dictionary = DictionaryStore(this)
        keys = ApiKeyStore(this)
    }
}

val Context.app: App get() = applicationContext as App
