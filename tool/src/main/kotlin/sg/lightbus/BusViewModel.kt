package sg.lightbus

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Page {
    data object Home : Page
    data object Settings : Page
    data object EditKey : Page
    data class Search(val initialQuery: String = "", val origin: BusStop? = null, val from: Page = Home) : Page
    data class Results(val query: String, val origin: BusStop? = null, val from: Page = Home) : Page
    data object Nearby : Page
    data class Stop(val stop: BusStop, val from: Page, val serviceNumber: String? = null) : Page
    data class Arrival(val stop: BusStop, val number: String, val from: Stop) : Page
    data class Service(
        val stop: BusStop,
        val number: String,
        val direction: Int?,
        val destination: BusStop?,
        val from: Page,
    ) : Page
    data class Direct(val origin: BusStop, val destination: BusStop, val from: Page) : Page
}

data class BusState(
    val page: Page = Page.Home,
    val favorites: List<BusStop> = emptyList(),
    val pins: List<PinnedService> = emptyList(),
    val keyReady: Boolean = false,
    val keySaved: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val searchResults: List<BusStop>? = null,
    val nearbyResults: List<NearbyStop>? = null,
    val nearbyAttempt: Int = 0,
    val active: Boolean = false,
    val arrivals: List<LiveService> = emptyList(),
    val arrivalsAt: Instant? = null,
    val arrivalsLoading: Boolean = false,
    val arrivalError: String? = null,
    val routes: Map<String, List<String>>? = null,
    val routesLoading: Boolean = false,
    val routeError: String? = null,
    val stopDirectory: Map<String, BusStop> = emptyMap(),
    val routeStops: List<BusRouteStop>? = null,
    val services: List<BusService>? = null,
    val directServices: List<DirectBus>? = null,
    val offlineReady: Boolean = false,
    val offlineDownloading: Boolean = false,
    val catalogOffline: Boolean = false,
)

class BusViewModel(
    private val dataMall: DataMall,
    private val preferences: BusPreferences,
) : LightViewModel<Unit>() {
    private val mutableState = MutableStateFlow(BusState())
    val state = mutableState.asStateFlow()

    private var key: String? = null
    private var catalogJob: Job? = null
    private var arrivalJob: Job? = null
    private var routeJob: Job? = null
    private var directoryJob: Job? = null
    private var lastArrivalAttempt = 0L
    private var lastRouteAttempt: Long? = null
    private var loadedSearch: Page.Results? = null
    private var arrivalsPage: Page.Stop? = null

    init {
        viewModelScope.launch {
            try {
                val favorites = preferences.favorites()
                val pins = preferences.pins()
                mutableState.update { it.copy(favorites = favorites, pins = pins) }
                key = preferences.readKey()
                updateOfflineStatus()
            } catch (error: Exception) {
                error.rethrowCancellation()
                mutableState.update { it.copy(message = "Could not read saved app settings.") }
            } finally {
                mutableState.update { it.copy(keyReady = true, keySaved = key != null) }
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        mutableState.update { it.copy(active = true) }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        mutableState.update { it.copy(active = false) }
    }

    override fun onAppPause() {
        mutableState.update { it.copy(active = false) }
    }

    override fun onBackPressed(): Boolean {
        val current = state.value
        if (current.saving) return true
        val previous = when (val page = current.page) {
            Page.Home -> return false
            Page.Settings, Page.Nearby -> Page.Home
            Page.EditKey -> Page.Settings
            is Page.Search -> page.from
            is Page.Results -> Page.Search(page.query, page.origin, page.from)
            is Page.Stop -> page.from
            is Page.Arrival -> page.from
            is Page.Service -> page.from
            is Page.Direct -> page.from
        }
        catalogJob?.cancel()
        arrivalJob?.cancel()
        arrivalJob = null
        show(previous)
        when (previous) {
            is Page.Results -> if (loadedSearch != previous) search(previous.query)
            is Page.Direct -> if (current.directServices == null) loadDirect(previous)
            is Page.Stop -> {
                if (arrivalsPage != previous) clearArrivals(previous)
                lastArrivalAttempt = 0
                refreshArrivals()
            }
            else -> Unit
        }
        return true
    }

    fun openSettings() = show(Page.Settings)
    fun editKey() = show(Page.EditKey)

    fun saveKey(input: CharSequence) {
        if (state.value.saving) return
        val value = input.toString().trim()
        if (value.isEmpty() || value.length > 512 || value.any { it !in '!'..'~' }) {
            mutableState.update { it.copy(message = "Enter the DataMall key without spaces.") }
            return
        }
        mutableState.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                dataMall.validateKey(value)
            } catch (error: Exception) {
                error.rethrowCancellation()
                val message = when ((error as? DataMallError)?.httpStatus) {
                    401, 403 -> "LTA rejected this key. Check every character and try again."
                    429 -> "LTA is limiting requests. Wait a minute and try again."
                    else -> "Could not check the key with LTA. Check your connection and try again."
                }
                mutableState.update { it.copy(saving = false, message = message) }
                return@launch
            }
            try {
                preferences.saveKey(value)
                routeJob?.cancel()
                directoryJob?.cancel()
                lastRouteAttempt = null
                key = value
                mutableState.update {
                    it.copy(
                        page = Page.Settings, keySaved = true, routesLoading = false,
                        routes = null, routeError = null, message = "Key verified and saved.",
                    )
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                mutableState.update { it.copy(message = "Could not save the DataMall key.") }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    fun openSearch() = show(Page.Search())

    fun openDirectSearch(origin: BusStop) = show(Page.Search(origin = origin, from = state.value.page))

    fun search(input: CharSequence) {
        val query = input.toString().trim()
        if (query.isBlank()) {
            mutableState.update { it.copy(message = "Enter a stop name, road or code.") }
            return
        }
        val current = state.value.page
        val page = when (current) {
            is Page.Search -> Page.Results(query, current.origin, current.from)
            is Page.Results -> current.copy(query = query)
            else -> return
        }
        val accountKey = key
        catalogJob?.cancel()
        mutableState.update { it.copy(page = page, searchResults = null, message = null, catalogOffline = false) }
        catalogJob = viewModelScope.launch {
            try {
                val results = if (isStopCode(query) && accountKey != null) {
                    listOfNotNull(dataMall.stopByCode(query, accountKey)).ifEmpty {
                        listOfNotNull(loadStopDirectory(accountKey).firstOrNull { it.code == query })
                    }
                } else {
                    val stops = loadStopDirectory(accountKey)
                    withContext(Dispatchers.Default) { findStops(stops, query) }
                }
                if (state.value.page === page) {
                    loadedSearch = page
                    val savedCopy = accountKey == null || dataMall.hasStaleCatalogs()
                    if (state.value.page === page) {
                        mutableState.update { it.copy(searchResults = results, catalogOffline = savedCopy) }
                    }
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                if (state.value.page === page) {
                    mutableState.update { it.copy(message = error.display("Could not load bus stops.")) }
                }
            }
        }
    }

    fun openNearby() {
        mutableState.update {
            it.copy(
                page = Page.Nearby, nearbyResults = null, message = null, catalogOffline = false,
                nearbyAttempt = it.nearbyAttempt + 1,
            )
        }
    }

    fun retryNearby() {
        if (state.value.page == Page.Nearby) openNearby()
    }

    fun nearbyFailure(message: String) {
        if (state.value.page == Page.Nearby) mutableState.update { it.copy(message = message) }
    }

    suspend fun showNearby(latitude: Double, longitude: Double) {
        try {
            val stops = loadStopDirectory(key)
            val results = withContext(Dispatchers.Default) { nearbyStops(stops, latitude, longitude) }
            val savedCopy = key == null || dataMall.hasStaleCatalogs()
            if (state.value.page == Page.Nearby) {
                mutableState.update { it.copy(nearbyResults = results, message = null, catalogOffline = savedCopy) }
            }
        } catch (error: Exception) {
            error.rethrowCancellation()
            nearbyFailure(error.display("Could not load bus stops."))
        }
    }

    fun openPinned(pin: PinnedService) = openStop(pin.stop, pin.number)

    fun openStop(stop: BusStop, serviceNumber: String? = null) {
        val origin = state.value.page
        arrivalJob?.cancel()
        arrivalJob = null
        lastArrivalAttempt = 0
        val page = Page.Stop(stop, origin, serviceNumber)
        show(page)
        clearArrivals(page)
        preloadStopDirectory()
        refreshArrivals()
    }

    private fun clearArrivals(page: Page.Stop) {
        arrivalsPage = page
        mutableState.update {
            it.copy(
                arrivals = emptyList(),
                arrivalsAt = null, arrivalsLoading = false,
                arrivalError = if (it.keyReady && key == null) "Add a DataMall key in Settings for live arrivals." else null,
            )
        }
    }

    fun refreshArrivals() {
        val visiblePage = state.value.page
        val page = when (visiblePage) {
            is Page.Stop -> visiblePage
            is Page.Arrival -> visiblePage.from
            else -> return
        }
        val accountKey = key
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val lastRouteRefresh = lastRouteAttempt
        if (state.value.routes == null && routeJob?.isActive != true &&
            (lastRouteRefresh == null || elapsed - lastRouteRefresh >= 60_000L)
        ) {
            lastRouteAttempt = elapsed
            loadRoutes(accountKey)
        }
        if (accountKey == null) return
        if (arrivalJob?.isActive == true ||
            (lastArrivalAttempt != 0L && elapsed - lastArrivalAttempt < 5_000L)
        ) return
        lastArrivalAttempt = elapsed
        mutableState.update { it.copy(arrivalsLoading = true, arrivalError = null) }
        arrivalJob = viewModelScope.launch {
            try {
                val arrivals = dataMall.arrivals(page.stop.code, accountKey, page.serviceNumber)
                if (state.value.page === visiblePage) {
                    mutableState.update { it.copy(arrivals = arrivals, arrivalsAt = Instant.now()) }
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                if (state.value.page === visiblePage) {
                    mutableState.update { it.copy(arrivalError = error.display("Could not refresh arrivals.")) }
                }
            } finally {
                if (state.value.page === visiblePage) mutableState.update { it.copy(arrivalsLoading = false) }
            }
        }
    }

    fun refreshIfDue() {
        if (android.os.SystemClock.elapsedRealtime() - lastArrivalAttempt >= 30_000L) {
            refreshArrivals()
        }
    }

    fun toggleFavorite(stop: BusStop) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val favorites = preferences.toggleFavorite(stop)
                mutableState.update { it.copy(favorites = favorites, message = null) }
            } catch (error: Exception) {
                error.rethrowCancellation()
                mutableState.update { it.copy(message = "Could not save this favorite.") }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    fun openArrival(stop: BusStop, number: String) {
        val from = state.value.page as? Page.Stop ?: return
        arrivalJob?.cancel()
        arrivalJob = null
        show(Page.Arrival(stop, number, from))
        mutableState.update { it.copy(arrivalsLoading = false) }
    }

    fun openService(stop: BusStop, number: String, direction: Int? = null, destination: BusStop? = null) {
        val page = Page.Service(stop, number, direction, destination, state.value.page)
        arrivalJob?.cancel()
        show(page)
        loadService(page)
    }

    fun retryService() {
        val page = state.value.page
        if (page is Page.Service) loadService(page)
    }

    private fun loadService(page: Page.Service) {
        catalogJob?.cancel()
        mutableState.update { it.copy(message = null, catalogOffline = false) }
        catalogJob = viewModelScope.launch {
            try {
                val routes = loadRouteDirectory(key)
                if (state.value.page !== page) return@launch
                mutableState.update { it.copy(routeStops = routes) }
                val stops = optionalCatalog { loadStopDirectory(key) }
                val services = optionalCatalog {
                    key?.let { dataMall.services(it) } ?: dataMall.cachedServices()
                }
                if (state.value.page === page) {
                    mutableState.update {
                        it.copy(
                            stopDirectory = it.stopDirectory + stops.orEmpty().associateBy(BusStop::code),
                            services = services ?: it.services,
                        )
                    }
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                if (state.value.page === page) {
                    mutableState.update { it.copy(message = error.display("Could not load this route.")) }
                }
            }
        }
    }

    fun findDirectBuses(origin: BusStop, destination: BusStop) {
        val page = Page.Direct(origin, destination, state.value.page)
        show(page)
        loadDirect(page)
    }

    fun retryDirect() {
        (state.value.page as? Page.Direct)?.let(::loadDirect)
    }

    private fun loadDirect(page: Page.Direct) {
        catalogJob?.cancel()
        mutableState.update { it.copy(directServices = null, message = null, catalogOffline = false) }
        catalogJob = viewModelScope.launch {
            try {
                val routes = loadRouteDirectory(key)
                val rides = withContext(Dispatchers.Default) {
                    directBuses(routes, page.origin.code, page.destination.code)
                }
                if (state.value.page === page) mutableState.update { it.copy(directServices = rides) }
            } catch (error: Exception) {
                error.rethrowCancellation()
                if (state.value.page === page) {
                    mutableState.update { it.copy(message = error.display("Could not find direct buses.")) }
                }
            }
        }
    }

    fun togglePin(stop: BusStop, number: String) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val pins = preferences.togglePin(PinnedService(stop, number))
                mutableState.update { it.copy(pins = pins, message = null) }
            } catch (error: Exception) {
                error.rethrowCancellation()
                mutableState.update { it.copy(message = "Could not save this pinned service.") }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    fun downloadOfflineData() {
        if (state.value.offlineDownloading) return
        val accountKey = key
        if (accountKey == null) {
            mutableState.update { it.copy(message = "Add a DataMall key to download the directory.") }
            return
        }
        mutableState.update { it.copy(offlineDownloading = true, message = null) }
        viewModelScope.launch {
            try {
                val ready = dataMall.downloadCatalogs(accountKey)
                val stale = dataMall.hasStaleCatalogs()
                mutableState.update {
                    it.copy(
                        offlineReady = ready,
                        message = when {
                            !ready -> "Could not save the full directory. Try again."
                            stale -> "The previous offline copy is available. Try again to update it."
                            else -> "Offline directory ready."
                        }.takeIf { _ -> it.page == Page.Settings },
                    )
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                if (state.value.page == Page.Settings) {
                    mutableState.update { it.copy(message = error.display("Could not download the directory.")) }
                }
            } finally {
                mutableState.update { it.copy(offlineDownloading = false) }
            }
        }
    }

    private suspend fun loadStopDirectory(accountKey: String?): List<BusStop> {
        val stops = if (accountKey != null) dataMall.stops(accountKey) else dataMall.cachedStops()
        if (stops.isEmpty()) throw DataMallError("Add a DataMall key in Settings to load bus stops.")
        val directory = withContext(Dispatchers.Default) { stops.associateBy(BusStop::code) }
        mutableState.update { it.copy(stopDirectory = directory) }
        return stops
    }

    private fun preloadStopDirectory() {
        if (state.value.stopDirectory.isNotEmpty() || directoryJob?.isActive == true) return
        val accountKey = key
        directoryJob = viewModelScope.launch { optionalCatalog { loadStopDirectory(accountKey) } }
    }

    private suspend fun loadRouteDirectory(accountKey: String?): List<BusRouteStop> {
        val routes = if (accountKey != null) dataMall.routeStops(accountKey) else dataMall.cachedRouteStops()
        if (routes.isEmpty()) throw DataMallError("Download the route directory in Settings first.")
        val offline = accountKey == null || dataMall.hasStaleCatalogs()
        mutableState.update { it.copy(catalogOffline = offline) }
        return routes
    }

    private suspend fun updateOfflineStatus() {
        val ready = dataMall.isOfflineCopyReady()
        mutableState.update { it.copy(offlineReady = ready) }
    }

    private fun loadRoutes(accountKey: String?) {
        mutableState.update { it.copy(routesLoading = true, routeError = null) }
        routeJob = viewModelScope.launch {
            try {
                val routes = if (accountKey != null) dataMall.routes(accountKey) else {
                    withContext(Dispatchers.Default) {
                        dataMall.cachedRouteStops().groupBy({ it.stopCode }, { it.service })
                            .mapValues { (_, services) -> services.distinct() }
                    }
                }
                mutableState.update { it.copy(routes = routes) }
            } catch (error: Exception) {
                error.rethrowCancellation()
                mutableState.update {
                    it.copy(routeError = error.display("The full service list is unavailable."))
                }
            } finally {
                mutableState.update { it.copy(routesLoading = false) }
            }
        }
    }

    private fun show(page: Page) {
        mutableState.update { it.copy(page = page, message = null, catalogOffline = false) }
    }
}

private suspend fun <T> optionalCatalog(load: suspend () -> T): T? = try {
    load()
} catch (error: Exception) {
    error.rethrowCancellation()
    null
}

private fun Exception.rethrowCancellation() {
    if (this is CancellationException) throw this
}

private fun Exception.display(fallback: String): String =
    if (this is DataMallError) message ?: fallback else "$fallback Check your connection and try again."
