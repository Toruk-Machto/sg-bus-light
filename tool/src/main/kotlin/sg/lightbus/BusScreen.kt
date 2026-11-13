package sg.lightbus

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.callRemoteServiceMethod
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@InitialScreen
class BusScreen(activity: SealedLightActivity) : LightScreen<Unit, BusViewModel>(activity) {
    override val viewModelClass = BusViewModel::class.java

    override fun createViewModel() = BusViewModel(
        DataMall(lightContext.filesDir),
        BusPreferences(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val colors by LightThemeController.colors.collectAsState()
        val back = { goBack() }

        LightTheme(colors = colors) {
            Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
                when (val page = state.page) {
                    Page.Home -> Home(state, viewModel, back)
                    Page.Settings -> Settings(state, viewModel, back)
                    Page.EditKey -> Editor(
                        title = "DataMall key", initialText = "", message = state.message,
                        busy = state.saving, submitLabel = "CHECK & SAVE", submit = viewModel::saveKey, back = back,
                    )
                    is Page.Search -> Editor(
                        title = if (page.origin == null) "Name, road or stop code" else "Destination stop",
                        initialText = page.initialQuery,
                        message = state.message, busy = false, submitLabel = "SEARCH",
                        submit = viewModel::search, back = back,
                    )
                    is Page.Results -> SearchResults(state, page, viewModel, back)
                    Page.Nearby -> Nearby(state, viewModel, back)
                    is Page.Stop -> StopDetails(state, page, viewModel, back)
                    is Page.Arrival -> ArrivalDetails(state, page, viewModel, back)
                    is Page.Service -> ServiceDetails(state, page, viewModel, back)
                    is Page.Direct -> DirectBuses(state, page, viewModel, back)
                }
            }
        }
    }
}

@Composable
private fun Home(state: BusState, model: BusViewModel, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("SG Bus", back, "Settings", model::openSettings)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                Row(Modifier.padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelButton("Nearby", model::openNearby, Modifier.weight(1f))
                    PanelButton("Search", model::openSearch, Modifier.weight(1f), selected = true)
                }
                state.message?.let { Note(it) }
                if (state.keyReady && !state.keySaved) {
                    MenuRow("Set up DataMall key", "Required for live arrivals", model::openSettings)
                }
            }
            if (state.pins.isNotEmpty()) {
                item { SectionTitle("Pinned buses") }
                items(state.pins, key = { "pin-${it.stop.code}-${it.number}" }) { pin ->
                    MenuRow("${pin.number} · ${pin.stop.name}", pin.stop.code) { model.openPinned(pin) }
                }
            }
            item {
                SectionTitle("Saved stops")
                when {
                    !state.keyReady -> Note("Loading saved stops…")
                    state.favorites.isEmpty() -> Note("Open a stop and tap Save to add it here.")
                }
            }
            items(state.favorites, key = { "stop-${it.code}" }) { stop ->
                StopRow(stop, null) { model.openStop(stop) }
            }
        }
    }
}

@Composable
private fun Settings(state: BusState, model: BusViewModel, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("Settings", back)
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            item {
                state.message?.let { Note(it) }
                SectionTitle("LTA DataMall")
                val keyStatus = if (state.keySaved) "Account key saved on this phone."
                else "Enter the Account Key from your registration email."
                MenuRow(if (state.keySaved) "Replace key" else "Enter key", keyStatus, model::editKey)
            }
            item {
                SectionTitle("Offline browsing")
                Note(
                    when {
                        state.offlineDownloading -> "Downloading stops, routes and schedules…"
                        state.offlineReady -> "Stops, routes and schedules are saved on this phone."
                        else -> "Save stops, routes and schedules to browse without a connection."
                    },
                )
                Action(
                    if (state.offlineReady) "Update offline data" else "Download offline data",
                    model::downloadOfflineData,
                    enabled = state.keySaved && !state.offlineDownloading,
                )
            }
            item {
                SectionTitle("Data sources")
                Note("Bus arrival, stop, route and service data from LTA DataMall. Singapore Open Data Licence v1.0.")
                SelectionContainer {
                    Note("https://datamall.lta.gov.sg/content/datamall/en/SingaporeOpenDataLicence.html")
                }
            }
        }
    }
}

@Composable
private fun Editor(
    title: String,
    initialText: String,
    message: String?,
    busy: Boolean,
    submitLabel: String,
    submit: (CharSequence) -> Unit,
    back: () -> Unit,
) {
    val text = rememberTextFieldState(initialText)
    val keyboard = rememberKeyboardOptions()
    Box(Modifier.fillMaxSize()) {
        LightTextInputEditor(
            title = if (busy) "Checking with LTA…" else title,
            state = text,
            onSubmit = { if (!busy) submit(it) },
            onBack = back,
            keyboardOptionsFlow = keyboard,
            singleLine = true,
            submitLabel = submitLabel,
        )
        if (message != null) {
            LightText(
                message, LightTextVariant.Paragraph,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 96.dp),
            )
        }
    }
}

@Composable
private fun SearchResults(state: BusState, page: Page.Results, model: BusViewModel, back: () -> Unit) {
    val results = state.searchResults
    Column(Modifier.fillMaxSize()) {
        Header(if (page.origin == null) "Search results" else "Choose destination", back)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Note(page.query)
            OfflineNote(state)
            when {
                state.message != null -> {
                    Note(state.message)
                    Action("Try again", { model.search(page.query) })
                }
                results == null -> Note("Searching the stop directory…")
                results.isEmpty() -> Note("No matching stops.")
                else -> LazyColumn {
                    items(results, key = { it.code }) { stop ->
                        StopRow(stop, null) {
                            val origin = page.origin
                            if (origin == null) model.openStop(stop) else model.findDirectBuses(origin, stop)
                        }
                    }
                }
            }
        }
    }
}

private const val LOCATION_PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

@Composable
private fun Nearby(state: BusState, model: BusViewModel, back: () -> Unit) {
    val launcher = rememberPermissionRequestLauncher(LOCATION_PERMISSION)
    var needsPermission by remember { mutableStateOf(false) }
    val results = state.nearbyResults

    LaunchedEffect(state.active, state.nearbyAttempt, results == null) {
        if (!state.active || results != null) return@LaunchedEffect
        val permission = withTimeoutOrNull(4_000) {
            checkPermission(LOCATION_PERMISSION).getOrNull()?.permissionResult
        }
        needsPermission = permission == LightServiceMethod.GetPermission.Result.Denied
        if (permission != LightServiceMethod.GetPermission.Result.Granted) {
            model.nearbyFailure(
                if (needsPermission) "Allow location to find nearby stops."
                else "LightOS location is unavailable. You can search by stop name or code.",
            )
            return@LaunchedEffect
        }
        val position = currentPosition()
        if (position == null) {
            model.nearbyFailure("No GPS fix yet. Try again from a spot with a clearer view of the sky.")
        } else {
            model.showNearby(position.first, position.second)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Nearby stops", back, "Retry", model::retryNearby)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            OfflineNote(state)
            when {
                state.message != null -> {
                    Note(state.message)
                    if (needsPermission && launcher != null) {
                        Action("Allow location", launcher::launch)
                    }
                }
                results == null -> Note("Finding your location and nearby stops…")
                results.isEmpty() -> Note("No bus stops within 2 km.")
                else -> LazyColumn {
                    items(results, key = { it.stop.code }) { result ->
                        StopRow(result.stop, result.metres) { model.openStop(result.stop) }
                    }
                }
            }
        }
    }
}

private suspend fun currentPosition(): Pair<Double, Double>? {
    try {
        return withTimeoutOrNull(20_000) {
            callRemoteServiceMethod(LightServiceMethod.RequestLocationUpdates, Unit)
            while (true) {
                val fix = callRemoteServiceMethod(LightServiceMethod.GetCurrentLocation, Unit).getOrNull()
                val latitude = fix?.latitude
                val longitude = fix?.longitude
                val age = fix?.timestampMs?.let { System.currentTimeMillis() - it }
                if (latitude != null && longitude != null && latitude in -90.0..90.0 &&
                    longitude in -180.0..180.0 && age != null && age in 0..90_000 &&
                    (fix?.accuracyMeters ?: Double.POSITIVE_INFINITY) <= 1_000
                ) {
                    return@withTimeoutOrNull latitude to longitude
                }
                delay(1_000)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    } finally {
        withContext(NonCancellable) {
            withTimeoutOrNull(2_000) {
                callRemoteServiceMethod(LightServiceMethod.ReleaseLocationUpdates, Unit)
            }
        }
    }
}

private val singaporeTime = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Singapore"))

@Composable
private fun arrivalClock(active: Boolean, stopCode: String, model: BusViewModel): Instant {
    var now by remember(stopCode) { mutableStateOf(Instant.now()) }
    LaunchedEffect(active, stopCode) {
        if (!active) return@LaunchedEffect
        while (true) {
            now = Instant.now()
            model.refreshIfDue()
            delay(5_000)
        }
    }
    return now
}

@Composable
private fun StopDetails(state: BusState, page: Page.Stop, model: BusViewModel, back: () -> Unit) {
    val stop = page.stop
    val pinnedNumber = page.serviceNumber
    val saved = state.favorites.any { it.code == stop.code }
    val pinned = state.pins.any { it.stop.code == stop.code && it.number == pinnedNumber }
    val services = remember(state.routes, state.arrivals, stop.code, pinnedNumber) {
        val numbers = pinnedNumber?.let(::listOf) ?: state.routes?.get(stop.code).orEmpty()
        val live = if (pinnedNumber == null) state.arrivals else state.arrivals.filter { it.number == pinnedNumber }
        mergeServices(numbers, live)
    }
    val now = arrivalClock(state.active, stop.code, model)
    val updated = state.arrivalsAt
    val stale = updated != null && now.isAfter(updated.plusSeconds(90))

    Column(Modifier.fillMaxSize()) {
        if (pinnedNumber != null) {
            Header("Bus $pinnedNumber", back, if (pinned) "Unpin" else "Pin") {
                model.togglePin(stop, pinnedNumber)
            }
        } else {
            Header("Stop ${stop.code}", back, if (saved) "Unsave" else "Save") { model.toggleFavorite(stop) }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                LightText(stop.name, LightTextVariant.Heading, maxLines = 2)
                val place = if (pinnedNumber == null) stop.road else "${stop.code} · ${stop.road}"
                Note(place)
                MenuRow(
                    title = if (pinnedNumber == null) "Find direct bus" else "View all buses here",
                    detail = null,
                    select = if (pinnedNumber == null) ({ model.openDirectSearch(stop) }) else ({ model.openStop(stop) }),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightText(
                        when {
                            state.arrivalsLoading -> "Updating…"
                            updated == null -> "Arrivals"
                            stale -> "Stale · ${singaporeTime.format(updated)}"
                            else -> "As of ${singaporeTime.format(updated)}"
                        },
                        LightTextVariant.Detail,
                    )
                    Action("Refresh", model::refreshArrivals, enabled = state.keySaved && !state.arrivalsLoading)
                }
                OfflineNote(state)
                state.message?.let { Note(it) }
                state.arrivalError?.let { Note(it) }
                state.routeError?.let { Note(it) }
                if (state.routesLoading && services.isEmpty()) Note("Loading bus services…")
                if (updated != null && services.none { row -> row.times.any { it != null } }) {
                    Note("No arrival estimates available right now.")
                }
                if (services.isNotEmpty()) ArrivalHeading()
            }
            items(services, key = { it.number }) { service ->
                val times = if (stale) List(3) { null } else service.times
                val first = if (stale) null else service.predictions.firstOrNull()
                ArrivalRow(
                    number = service.number,
                    labels = times.map { etaLabel(it, now) },
                    first = first,
                    directory = state.stopDirectory,
                    route = { model.openService(stop, service.number) },
                    details = { model.openArrival(stop, service.number) },
                )
            }
        }
    }
}

@Composable
private fun ArrivalHeading() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText("Route", LightTextVariant.Detail, modifier = Modifier.weight(1.1f), lighten = true)
        Row(Modifier.weight(3f)) {
            listOf("Next", "2nd", "3rd").forEach { label ->
                LightText(
                    label, LightTextVariant.Detail, modifier = Modifier.weight(1f),
                    lighten = true, align = TextAlign.Center,
                )
            }
        }
    }
    Divider()
}

@Composable
private fun ArrivalRow(
    number: String,
    labels: List<String>,
    first: ArrivalPrediction?,
    directory: Map<String, BusStop>,
    route: () -> Unit,
    details: () -> Unit,
) {
    val destination = stopName(directory, first?.destinationCode.orEmpty())
    val attributes = first?.let { prediction ->
        listOfNotNull(prediction.crowdingLabel, "Scheduled".takeIf { prediction.scheduled })
    }.orEmpty()
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1.1f)) {
                Action(number, route, clickLabel = "View bus $number route")
            }
            Row(
                Modifier.weight(3f).heightIn(min = 44.dp)
                    .lightClickable(role = Role.Button, onClickLabel = "View bus $number arrivals", onClick = details),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    LightText(
                        label, if (index == 0) LightTextVariant.Subheading else LightTextVariant.Copy,
                        align = TextAlign.Center, lighten = label == "—", monospace = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (destination.isNotEmpty() || attributes.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth()
                    .lightClickable(role = Role.Button, onClickLabel = "View bus $number arrivals", onClick = details)
                    .padding(start = 4.dp, bottom = 8.dp),
            ) {
                if (destination.isNotEmpty()) {
                    LightText(
                        "To $destination", LightTextVariant.Paragraph, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (attributes.isNotEmpty()) {
                    LightText(attributes.joinToString(" · "), LightTextVariant.Detail, lighten = true)
                }
            }
        }
    }
    Divider()
}

@Composable
private fun ArrivalDetails(state: BusState, page: Page.Arrival, model: BusViewModel, back: () -> Unit) {
    val stop = page.stop
    val pinned = state.pins.any { it.stop.code == stop.code && it.number == page.number }
    val now = arrivalClock(state.active, stop.code, model)
    val updated = state.arrivalsAt
    val stale = updated != null && now.isAfter(updated.plusSeconds(90))
    val arrivals = remember(state.arrivals, page.number) {
        mergeServices(listOf(page.number), state.arrivals.filter { it.number == page.number }).single()
    }
    Column(Modifier.fillMaxSize()) {
        Header("Bus ${page.number} arrivals", back, if (pinned) "Unpin" else "Pin") {
            model.togglePin(stop, page.number)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                LightText(stop.name, LightTextVariant.Heading, maxLines = 2)
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Note(
                        when {
                            state.arrivalsLoading -> "Updating…"
                            stale && updated != null -> "Stale · ${singaporeTime.format(updated)}"
                            updated != null -> "As of ${singaporeTime.format(updated)}"
                            else -> "Arrival estimates"
                        },
                    )
                    Action("Refresh", model::refreshArrivals, enabled = state.keySaved && !state.arrivalsLoading)
                }
                MenuRow("View route", "Stops and first / last buses") { model.openService(stop, page.number) }
                state.message?.let { Note(it) }
                state.arrivalError?.let { Note(it) }
                if (!state.arrivalsLoading && (stale || arrivals.times.none { it != null })) {
                    Note("No current arrival estimates.")
                }
            }
            if (!stale) {
                items(3) { index ->
                    val prediction = arrivals.predictions.getOrNull(index)
                    val eta = etaLabel(arrivals.times.getOrNull(index), now)
                    if (prediction != null && eta != "—") {
                        ArrivalCard(index, eta, prediction, state.stopDirectory)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrivalCard(
    index: Int,
    eta: String,
    prediction: ArrivalPrediction,
    directory: Map<String, BusStop>,
) {
    val destination = stopName(directory, prediction.destinationCode)
    val vehicleType = when (prediction.vehicleType) {
        "SD" -> "Single-deck"
        "DD" -> "Double-deck"
        "BD" -> "Articulated"
        else -> null
    }
    val attributes = listOfNotNull(
        prediction.crowdingLabel, vehicleType, "Scheduled".takeIf { prediction.scheduled },
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LightText(listOf("Next bus", "2nd bus", "3rd bus")[index], LightTextVariant.Detail, lighten = true)
            LightText(eta, LightTextVariant.Subheading, monospace = true)
        }
        if (destination.isNotEmpty()) LightText("To $destination", LightTextVariant.Copy)
        if (attributes.isNotEmpty()) Note(attributes.joinToString(" · "))
    }
    Divider()
}

@Composable
private fun ServiceDetails(state: BusState, page: Page.Service, model: BusViewModel, back: () -> Unit) {
    val stop = page.stop
    val number = page.number
    val routes = remember(state.routeStops, number) {
        state.routeStops.orEmpty().filter { it.service == number }.groupBy { it.direction }
            .toSortedMap().mapValues { (_, stops) -> stops.sortedBy { it.sequence } }
    }
    val defaultDirection = page.direction?.takeIf { it in routes }
        ?: routes.entries.firstOrNull { (_, stops) -> stops.any { it.stopCode == stop.code } }?.key
        ?: routes.keys.firstOrNull()
    var selectedDirection by remember(page, defaultDirection) { mutableStateOf(defaultDirection) }
    val orderedStops = routes[selectedDirection].orEmpty()
    val service = state.services.orEmpty().firstOrNull { it.number == number && it.direction == selectedDirection }
    val pinned = state.pins.any { it.stop.code == stop.code && it.number == number }
    val schedule = orderedStops.filter { it.stopCode == stop.code }
    var expandedSequence by remember(page, selectedDirection) { mutableStateOf<Int?>(null) }
    val destination = page.destination

    Column(Modifier.fillMaxSize()) {
        Header("Bus $number route", back, if (pinned) "Unpin" else "Pin") { model.togglePin(stop, number) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                OfflineNote(state)
                when {
                    state.message != null -> {
                        Note(state.message)
                        Action("Try again", model::retryService)
                    }
                    state.routeStops == null || state.routesLoading && routes.isEmpty() -> Note("Loading route…")
                    routes.isEmpty() -> Note("No published route for bus $number.")
                }
                if (orderedStops.isNotEmpty()) {
                    val terminalCode = service?.destinationCode.orEmpty().ifBlank { orderedStops.last().stopCode }
                    val routeTitle = service?.loopDescription.orEmpty().takeIf(String::isNotBlank)?.let { "Loops at $it" }
                        ?: stopName(state.stopDirectory, terminalCode).takeIf(String::isNotBlank)?.let { "To $it" }
                        ?: "Direction $selectedDirection"
                    LightText(routeTitle, LightTextVariant.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val operator = service?.operator.orEmpty().ifBlank { orderedStops.first().operator }
                    val category = service?.category.orEmpty()
                    if (operator.isNotBlank() || category.isNotBlank()) {
                        Note(listOf(operatorName(operator), category).filter(String::isNotBlank).joinToString(" · "))
                    }
                    if (routes.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            routes.keys.forEach { direction ->
                                PanelButton(
                                    "Direction $direction", { selectedDirection = direction }, Modifier.weight(1f),
                                    selected = selectedDirection == direction,
                                )
                            }
                        }
                    }
                    SectionTitle("First / last bus")
                    LightText(stop.name, LightTextVariant.Paragraph)
                    if (schedule.isEmpty()) {
                        Note("This direction does not call at ${stop.name}.")
                    } else {
                        schedule.forEachIndexed { index, entry ->
                            if (schedule.size > 1) Note("Visit ${index + 1}")
                            ScheduleTable(entry)
                        }
                    }
                    if (service != null) ServiceIntervals(service)
                    SectionTitle("Stops in order")
                    if (destination != null && selectedDirection == page.direction) {
                        Note("Board at ${stop.name} · Alight at ${destination.name}")
                    }
                }
            }
            items(orderedStops, key = { it.sequence }) { routeStop ->
                RouteStopRow(
                    routeStop = routeStop,
                    directory = state.stopDirectory,
                    boarding = routeStop.stopCode == stop.code,
                    alighting = selectedDirection == page.direction && routeStop.stopCode == destination?.code,
                    expanded = expandedSequence == routeStop.sequence,
                    select = {
                        expandedSequence = routeStop.sequence.takeUnless { it == expandedSequence }
                    },
                )
            }
        }
    }
}

private fun stopName(directory: Map<String, BusStop>, code: String): String {
    if (!isStopCode(code) || code == "00000") return ""
    return directory[code]?.name?.takeIf(String::isNotBlank) ?: "Stop $code"
}

private fun operatorName(code: String): String = when (code) {
    "SBST" -> "SBS Transit"
    "SMRT" -> "SMRT"
    "TTS" -> "Tower Transit"
    "GAS" -> "Go-Ahead"
    else -> code
}

private fun formatSchedule(value: String): String {
    if (value.length != 4 || value.any { it !in '0'..'9' }) return "—"
    val hour = value.take(2).toInt()
    val minute = value.takeLast(2).toInt()
    if (minute > 59 || hour > 47) return "—"
    val time = "${(hour % 24).toString().padStart(2, '0')}:${value.takeLast(2)}"
    return if (hour < 24) time else "$time +1"
}

@Composable
private fun ScheduleTable(stop: BusRouteStop) {
    val weekdays = formatSchedule(stop.weekdayFirst) to formatSchedule(stop.weekdayLast)
    val saturday = formatSchedule(stop.saturdayFirst) to formatSchedule(stop.saturdayLast)
    val sunday = formatSchedule(stop.sundayFirst) to formatSchedule(stop.sundayLast)
    ScheduleRow("Day", "First", "Last", heading = true)
    ScheduleRow("Weekdays", weekdays.first, weekdays.second)
    ScheduleRow("Saturday", saturday.first, saturday.second)
    ScheduleRow("Sunday", sunday.first, sunday.second)
    if (listOf(weekdays, saturday, sunday).any { (first, last) -> "+1" in first || "+1" in last }) {
        Note("+1 means after midnight the next day.")
    }
}

@Composable
private fun ScheduleRow(day: String, first: String, last: String, heading: Boolean = false) {
    val variant = if (heading) LightTextVariant.Detail else LightTextVariant.Paragraph
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        LightText(day, variant, modifier = Modifier.weight(1.5f), lighten = heading)
        LightText(first, variant, align = TextAlign.Center, modifier = Modifier.weight(1f), monospace = true, lighten = heading)
        LightText(last, variant, align = TextAlign.Center, modifier = Modifier.weight(1f), monospace = true, lighten = heading)
    }
    Divider()
}

@Composable
private fun ServiceIntervals(service: BusService) {
    val intervals = listOf(
        "Morning peak" to service.amPeak,
        "Daytime off-peak" to service.amOffpeak,
        "Evening peak" to service.pmPeak,
        "Evening off-peak" to service.pmOffpeak,
    ).filter { (_, value) -> value.any { it in '1'..'9' } }
    if (intervals.isNotEmpty()) {
        SectionTitle("Typical departures")
        intervals.forEach { (period, interval) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                LightText(period, LightTextVariant.Detail, lighten = true)
                LightText("${interval.replace("-", "–")} min", LightTextVariant.Detail, monospace = true)
            }
        }
    }
}

@Composable
private fun RouteStopRow(
    routeStop: BusRouteStop,
    directory: Map<String, BusStop>,
    boarding: Boolean,
    alighting: Boolean,
    expanded: Boolean,
    select: () -> Unit,
) {
    val stop = directory[routeStop.stopCode]
    val name = stop?.name.orEmpty().ifBlank { routeStop.stopCode }
    val labels = listOfNotNull(
        routeStop.stopCode,
        "Board".takeIf { boarding && !alighting },
        "Alight".takeIf { alighting },
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .lightClickable(role = Role.Button, onClickLabel = "First and last bus at $name", onClick = select)
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            routeStop.sequence.toString(), LightTextVariant.Detail, modifier = Modifier.weight(0.13f),
            monospace = true, lighten = !boarding && !alighting,
        )
        Column(Modifier.weight(1f)) {
            LightText(name, LightTextVariant.Paragraph, underline = boarding || alighting)
            LightText(labels.joinToString(" · "), LightTextVariant.Detail, lighten = true)
        }
        LightText(if (expanded) "−" else "+", LightTextVariant.Detail, lighten = true)
    }
    if (expanded) ScheduleTable(routeStop)
    Divider()
}

@Composable
private fun DirectBuses(state: BusState, page: Page.Direct, model: BusViewModel, back: () -> Unit) {
    val results = state.directServices
    Column(Modifier.fillMaxSize()) {
        Header("Direct buses", back)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                LightText(page.destination.name, LightTextVariant.Heading, maxLines = 2)
                Note("From ${page.origin.name} · ${page.origin.code}")
                Note("To ${page.destination.name} · ${page.destination.code}")
                OfflineNote(state)
                when {
                    state.message != null -> {
                        Note(state.message)
                        Action("Try again", model::retryDirect)
                    }
                    results == null -> Note("Checking routes…")
                    results.isEmpty() -> Note("No direct bus between these stops.")
                    else -> SectionTitle("Services")
                }
            }
            items(results.orEmpty(), key = { "${it.service}-${it.direction}" }) { ride ->
                val stops = ride.alight.sequence - ride.board.sequence
                val distance = (ride.alight.distance ?: 0.0) - (ride.board.distance ?: 0.0)
                val detail = buildList {
                    add(if (stops == 1) "1 stop" else "$stops stops")
                    if (ride.board.distance != null && ride.alight.distance != null && distance > 0) {
                        add(String.format(java.util.Locale.ROOT, "%.1f km", distance))
                    }
                }.joinToString(" · ")
                MenuRow("Bus ${ride.service}", detail) {
                    model.openService(page.origin, ride.service, ride.direction, page.destination)
                }
            }
        }
    }
}

@Composable
private fun StopRow(stop: BusStop, metres: Int?, select: () -> Unit) {
    val details = listOfNotNull(stop.code, stop.road.takeIf { it.isNotBlank() }, metres?.let { "${it}m" })
    MenuRow(stop.name, details.joinToString(" · "), select)
}

@Composable
private fun MenuRow(title: String, detail: String?, select: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .lightClickable(role = Role.Button, onClick = select).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            LightText(title, LightTextVariant.Copy, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) LightText(detail, LightTextVariant.Detail, lighten = true)
        }
        LightText("›", LightTextVariant.Subheading, lighten = true)
    }
    Divider()
}

@Composable
private fun SectionTitle(title: String) {
    LightText(
        title, LightTextVariant.Subheading,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun OfflineNote(state: BusState) {
    if (state.catalogOffline) Note("Showing saved offline data.")
}

@Composable
private fun PanelButton(label: String, select: () -> Unit, modifier: Modifier, selected: Boolean = false) {
    val colors = LightThemeTokens.colors
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier.heightIn(min = 46.dp)
            .background(if (selected) colors.content else colors.background, shape)
            .border(1.dp, if (selected) colors.content else colors.content.copy(alpha = 0.45f), shape)
            .lightClickable(role = Role.Button, onClick = select)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            label, LightTextVariant.Paragraph, align = TextAlign.Center,
            color = if (selected) colors.background else colors.content,
        )
    }
}

@Composable
private fun Header(
    title: String,
    back: () -> Unit,
    action: String? = null,
    perform: (() -> Unit)? = null,
) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = back),
        center = LightTopBarCenter.Text(title),
        rightButton = action?.let { LightBarButton.Text(it, onClick = perform) },
    )
}

@Composable
private fun Note(text: String) {
    if (text.isNotBlank()) {
        LightText(
            text, LightTextVariant.Detail, lighten = true,
            modifier = Modifier.padding(vertical = 5.dp),
        )
    }
}

@Composable
private fun Action(text: String, perform: () -> Unit, enabled: Boolean = true, clickLabel: String? = null) {
    Box(
        Modifier.heightIn(min = 44.dp)
            .lightClickable(enabled = enabled, role = Role.Button, onClickLabel = clickLabel, onClick = perform)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text, LightTextVariant.Button, underline = true, lighten = !enabled)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = LightThemeTokens.colors.content.copy(alpha = 0.24f))
}
