package com.tgfinder

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.tgfinder.data.SecurePrefs
import com.tgfinder.data.db.AppDatabase
import com.tgfinder.download.DownloadRepository
import com.tgfinder.download.DownloadService
import com.tgfinder.download.MediaStoreSaver
import com.tgfinder.search.SessionStore
import com.tgfinder.telegram.AuthState
import com.tgfinder.telegram.StreamCache
import com.tgfinder.telegram.TdClient
import com.tgfinder.tmdb.TmdbRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TgFinderApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .crossfade(true)
            .build()
}

/** Hand-rolled dependency container; one per process. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = SecurePrefs(app)
    val db = AppDatabase.create(app)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val td = TdClient(app, prefs)
    val tmdb = TmdbRepository(http, db.tmdb(), prefs)
    val downloads = DownloadRepository(app, td, db.downloads(), MediaStoreSaver(app), appScope)
    val cache = StreamCache(td, prefs, downloads)
    val session = SessionStore()

    fun start() {
        DownloadService.ensureChannel(app)
        td.start()
        appScope.launch {
            downloads.onAppStart()
            td.auth.filter { it == AuthState.Ready }.first()
            cache.trimIfNeeded()
        }
    }
}
