package sg.lightbus

import okhttp3.ResponseBody.Companion.toResponseBody
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightActivity
import kotlinx.coroutines.*

class BatteryChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            checks()
            result.putString("stream", "\nPASS: battery regression and lifecycle checks\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        }
    }
    private fun main(action: () -> Unit) = runOnMainSync(action)
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }
            Thread.sleep(20)
        }
    }
    private fun launch(): Activity = startActivitySync(Intent(Intent.ACTION_MAIN)
        .setClassName(targetContext.packageName, "com.thelightphone.sdk.LightActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun screen(activity: Activity): LightScreen<*, *> {
        val entries = LightActivity::class.java.getDeclaredField("backStack").apply { isAccessible = true }.get(activity) as List<*>
        val entry = entries.last()!!
        return entry.javaClass.getDeclaredField("screen").apply { isAccessible = true }.get(entry) as LightScreen<*, *>
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun checks() = runBlocking {
        val activity = launch()
        lateinit var screen: com.thelightphone.sdk.SimpleLightScreen<Unit>
        lateinit var actual: BusViewModel
        main {
            @Suppress("UNCHECKED_CAST")
            val shown = screen(activity) as LightScreen<Unit, BusViewModel>
            screen = shown
            actual = shown.viewModel
        }
        val started = java.util.concurrent.atomic.AtomicInteger()
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val blocking = java.util.concurrent.atomic.AtomicBoolean(true)
        val client = okhttp3.OkHttpClient.Builder().callTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                started.incrementAndGet()
                while (blocking.get() && !chain.call().isCanceled()) Thread.sleep(10)
                if (chain.call().isCanceled()) { cancelled.incrementAndGet(); throw java.io.IOException("cancelled fixture") }
                val body = when {
                    chain.request().url.encodedPath.endsWith("BusStops") -> """{"value":[{"BusStopCode":"17111","Description":"BLK 343","RoadName":"Test Road","Latitude":1.3,"Longitude":103.7}]}"""
                    chain.request().url.encodedPath.endsWith("BusRoutes") -> """{"value":[{"BusStopCode":"17111","ServiceNo":"99"}]}"""
                    chain.request().url.encodedPath.endsWith("BusServices") -> """{"value":[{"ServiceNo":"99","Direction":1}]}"""
                    else -> """{"BusStopCode":"17111","Services":[{"ServiceNo":"99"}]}"""
                }
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200).message("Fixture").body(body.toResponseBody()).build()
            }.build()
        val directory = java.io.File(targetContext.cacheDir, "battery-fixture").apply { deleteRecursively(); mkdirs() }
        val api = DataMall(directory, client)
        val data = kotlinx.coroutines.flow.MutableStateFlow<androidx.datastore.preferences.core.Preferences>(androidx.datastore.preferences.core.emptyPreferences())
        val preferences = BusPreferences(object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = data
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences = transform(data.value).also { data.value = it }
        })
        preferences.saveKey("fake-device-fixture-key")
        lateinit var model: BusViewModel
        main { model = BusViewModel(api, preferences); model.onScreenShow(screen) }
        await("settings loaded") { model.state.value.keyReady }
        main { model.openStop(BusStop("17111", "BLK 343", "Test Road", 1.3, 103.7)) }
        await("parallel foreground requests") { started.get() >= 3 }
        main { model.onAppPause() }
        await("all transport calls cancel") { client.dispatcher.runningCallsCount() == 0 }
        check(cancelled.get() >= 3) { "Leaving the app kept a request alive" }
        val requestsAtPause = started.get()
        repeat(5) { main { model.refreshIfDue() } }
        Thread.sleep(150)
        check(started.get() == requestsAtPause) { "Background refresh launched a request" }
        blocking.set(false)
        main { model.onScreenShow(screen) }
        await("arrivals resume") { model.state.value.arrivals.singleOrNull()?.number == "99" }
        await("directory and routes resume") { model.state.value.stopDirectory.containsKey("17111") && model.state.value.routes != null }
        main { model.openSettings(); model.downloadOfflineData() }
        await("offline copy complete") { model.state.value.offlineReady && !model.state.value.offlineDownloading }
        main { model.openSearch(); model.search("BLK 343") }
        await("cached search") { model.state.value.searchResults?.singleOrNull()?.code == "17111" }
        main { model.toggleFavorite(model.state.value.searchResults!!.single()) }
        await("favorite saved") { !model.state.value.saving && model.state.value.favorites.size == 1 }
        main { model.onAppPause() }
        val offlineDir = java.io.File(targetContext.cacheDir, "offline-resume-fixture").apply { deleteRecursively(); mkdirs() }
        lateinit var downloader: BusViewModel
        blocking.set(true)
        main { downloader = BusViewModel(DataMall(offlineDir, client), preferences); downloader.onScreenShow(screen) }
        await("offline settings loaded") { downloader.state.value.keyReady }
        val beforeDownload = started.get()
        main { downloader.openSettings(); downloader.downloadOfflineData() }
        await("offline request begins") { started.get() > beforeDownload }
        main { downloader.onAppPause() }
        await("offline request cancelled") { client.dispatcher.runningCallsCount() == 0 && !downloader.state.value.offlineDownloading }
        blocking.set(false)
        main { downloader.onScreenShow(screen) }
        await("offline download resumes and finishes") { downloader.state.value.offlineReady && !downloader.state.value.offlineDownloading }
        main {
            downloader.onAppPause()
            val owner = androidx.lifecycle.ViewModelStore()
            owner.put("download", downloader)
            owner.clear()
        }
        offlineDir.deleteRecursively()
        // A slow request is bounded by the configured whole-call deadline.
        blocking.set(true)
        val timeoutStart = SystemClock.elapsedRealtime()
        try { api.arrivals("17111", "fake-key"); error("Expected timeout") } catch (_: java.io.IOException) {}
        check(SystemClock.elapsedRealtime() - timeoutStart < 4_000) { "Whole-call timeout failed" }
        main {
            val owner = androidx.lifecycle.ViewModelStore()
            owner.put("fixture", model)
            owner.clear()
            activity.finish()
        }
        await("Activity teardown") { activity.isDestroyed }
        check(!actual.viewModelScope.coroutineContext[Job]!!.isActive) { "Activity leaked the screen ViewModel" }
        directory.deleteRecursively()
    }
}
