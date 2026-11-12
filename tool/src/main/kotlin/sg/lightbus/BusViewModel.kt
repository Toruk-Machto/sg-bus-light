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
    data class Search(val initialQuery: String = "") : Page
    data class Results(val query: String) : Page
    data object Nearby : Page
    data class Stop(val stop: BusStop, val from: Page) : Page
}

data class BusState(
    val page: Page = Page.Home,
    val favorites: List<BusStop> = emptyList(),
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
    private var lastArrivalAttempt = 0L
    private var lastRouteAttempt: Long? = null

    init {
        viewModelScope.launch {
            try {
                val favorites = preferences.favorites()
                mutableState.update { it.copy(favorites = favorites) }
                key = preferences.readKey()
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
            is Page.Search -> Page.Home
            is Page.Results -> Page.Search(page.query)
            is Page.Stop -> page.from
        }
        catalogJob?.cancel()
        arrivalJob?.cancel()
        show(previous)
        if (previous is Page.Results && current.searchResults == null) search(previous.query)
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
                preferences.saveKey(value)
                routeJob?.cancel()
                lastRouteAttempt = null
                key = value
                mutableState.update {
                    it.copy(
                        page = Page.Home, keySaved = true, routesLoading = false,
                        routes = null, routeError = null,
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

    fun openSearch() {
        if (requireKey()) show(Page.Search())
    }

    fun search(input: CharSequence) {
        val query = input.toString().trim()
        if (query.isBlank()) {
            mutableState.update { it.copy(message = "Enter a stop name, road or code.") }
            return
        }
        val accountKey = key ?: return
        val page = Page.Results(query)
        catalogJob?.cancel()
        mutableState.update { it.copy(page = page, searchResults = null, message = null) }
        catalogJob = viewModelScope.launch {
            try {
                val results = if (isStopCode(query)) {
                    listOfNotNull(dataMall.stopByCode(query, accountKey))
                } else {
                    val stops = dataMall.stops(accountKey)
                    withContext(Dispatchers.Default) { findStops(stops, query) }
                }
                if (state.value.page === page) mutableState.update { it.copy(searchResults = results) }
            } catch (error: Exception) {
                error.rethrowCancellation()
                if (state.value.page === page) {
                    mutableState.update { it.copy(message = error.display("Could not load bus stops.")) }
                }
            }
        }
    }

    fun openNearby() {
        if (!requireKey()) return
        mutableState.update {
            it.copy(
                page = Page.Nearby, nearbyResults = null, message = null,
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
        val accountKey = key ?: return
        try {
            val stops = dataMall.stops(accountKey)
            val results = withContext(Dispatchers.Default) { nearbyStops(stops, latitude, longitude) }
            if (state.value.page == Page.Nearby) {
                mutableState.update { it.copy(nearbyResults = results, message = null) }
            }
        } catch (error: Exception) {
            error.rethrowCancellation()
            nearbyFailure(error.display("Could not load bus stops."))
        }
    }

    fun openStop(stop: BusStop) {
        if (!requireKey()) return
        val origin = state.value.page
        arrivalJob?.cancel()
        lastArrivalAttempt = 0
        mutableState.update {
            it.copy(
                page = Page.Stop(stop, origin), message = null, arrivals = emptyList(),
                arrivalsAt = null, arrivalsLoading = false, arrivalError = null,
            )
        }
        refreshArrivals()
    }

    fun refreshArrivals() {
        val page = state.value.page as? Page.Stop ?: return
        val accountKey = key ?: return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (arrivalJob?.isActive == true ||
            (lastArrivalAttempt != 0L && elapsed - lastArrivalAttempt < 5_000L)
        ) return
        lastArrivalAttempt = elapsed
        mutableState.update { it.copy(arrivalsLoading = true, arrivalError = null) }
        arrivalJob = viewModelScope.launch {
            try {
                val arrivals = dataMall.arrivals(page.stop.code, accountKey)
                if (state.value.page === page) {
                    mutableState.update { it.copy(arrivals = arrivals, arrivalsAt = Instant.now()) }
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                if (state.value.page === page) {
                    mutableState.update { it.copy(arrivalError = error.display("Could not refresh arrivals.")) }
                }
            } finally {
                if (state.value.page === page) mutableState.update { it.copy(arrivalsLoading = false) }
            }
        }
        val lastRouteRefresh = lastRouteAttempt
        if (state.value.routes == null && routeJob?.isActive != true &&
            (lastRouteRefresh == null || elapsed - lastRouteRefresh >= 60_000L)
        ) {
            lastRouteAttempt = elapsed
            loadRoutes(accountKey)
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

    private fun loadRoutes(accountKey: String) {
        mutableState.update { it.copy(routesLoading = true, routeError = null) }
        routeJob = viewModelScope.launch {
            try {
                val routes = dataMall.routes(accountKey)
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
        mutableState.update { it.copy(page = page, message = null) }
    }

    private fun requireKey(): Boolean {
        if (key != null) return true
        if (state.value.keyReady) show(Page.Settings)
        return false
    }
}

private fun Exception.rethrowCancellation() {
    if (this is CancellationException) throw this
}

private fun Exception.display(fallback: String): String =
    if (this is DataMallError) message ?: fallback else "$fallback Check your connection and try again."
