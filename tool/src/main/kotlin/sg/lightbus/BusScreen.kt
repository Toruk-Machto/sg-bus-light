package sg.lightbus

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.ui.text.style.TextAlign
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
                    Page.Settings -> Settings(state, viewModel::editKey, back)
                    Page.EditKey -> Editor(
                        title = "DataMall key", initialText = "", message = state.message,
                        busy = state.saving, submitLabel = "SAVE", submit = viewModel::saveKey, back = back,
                    )
                    is Page.Search -> Editor(
                        title = "Name, road or stop code", initialText = page.initialQuery,
                        message = state.message, busy = false, submitLabel = "SEARCH",
                        submit = viewModel::search, back = back,
                    )
                    is Page.Results -> SearchResults(state, page, viewModel, back)
                    Page.Nearby -> Nearby(state, viewModel, back)
                    is Page.Stop -> StopDetails(state, page.stop, viewModel, back)
                }
            }
        }
    }
}

@Composable
private fun Home(state: BusState, model: BusViewModel, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("SG Bus", back, "Settings", model::openSettings)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Action("Nearby", model::openNearby)
                Action("Search", model::openSearch)
            }
            Divider()
            LightText(
                "Saved stops", LightTextVariant.Heading,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            state.message?.let { Note(it) }
            when {
                !state.keyReady -> Note("Loading saved stops…")
                !state.keySaved -> Action("Set up DataMall key", model::openSettings)
                state.favorites.isEmpty() -> Note("Open a stop and tap Save to add it here.")
            }
            LazyColumn {
                items(state.favorites, key = { it.code }) { stop ->
                    StopRow(stop, null) { model.openStop(stop) }
                }
            }
        }
    }
}

@Composable
private fun Settings(state: BusState, editKey: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("Settings", back)
        Column(Modifier.padding(horizontal = 16.dp)) {
            LightText("LTA DataMall", LightTextVariant.Heading)
            if (state.keySaved) {
                Note("Account key saved on this phone.")
                Action("Replace key", editKey)
            } else {
                Note("Enter the API Account Key from your LTA DataMall registration email.")
                Action("Enter key", editKey)
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
    LightTextInputEditor(
        title = if (busy) "Saving…" else message ?: title,
        state = text,
        onSubmit = { if (!busy) submit(it) },
        onBack = back,
        keyboardOptionsFlow = keyboard,
        singleLine = true,
        submitLabel = submitLabel,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SearchResults(state: BusState, page: Page.Results, model: BusViewModel, back: () -> Unit) {
    val results = state.searchResults
    Column(Modifier.fillMaxSize()) {
        Header("Search results", back)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Note(page.query)
            when {
                state.message != null -> {
                    Note(state.message)
                    Action("Try again", { model.search(page.query) })
                }
                results == null -> Note("Searching the stop directory…")
                results.isEmpty() -> Note("No matching stops.")
                else -> LazyColumn {
                    items(results, key = { it.code }) { stop ->
                        StopRow(stop, null) { model.openStop(stop) }
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
private fun StopDetails(state: BusState, stop: BusStop, model: BusViewModel, back: () -> Unit) {
    val saved = state.favorites.any { it.code == stop.code }
    val services = remember(state.routes, state.arrivals, stop.code) {
        mergeServices(state.routes?.get(stop.code).orEmpty(), state.arrivals)
    }
    var now by remember(stop.code) { mutableStateOf(Instant.now()) }
    LaunchedEffect(state.active, stop.code) {
        if (!state.active) return@LaunchedEffect
        while (true) {
            now = Instant.now()
            model.refreshIfDue()
            delay(5_000)
        }
    }
    val updated = state.arrivalsAt
    val stale = updated != null && now.isAfter(updated.plusSeconds(90))

    Column(Modifier.fillMaxSize()) {
        Header(
            "Stop ${stop.code}", back, if (saved) "Unsave" else "Save",
        ) { model.toggleFavorite(stop) }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            LightText(stop.name, LightTextVariant.Heading, maxLines = 2)
            Note(stop.road)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    when {
                        state.arrivalsLoading -> "Updating…"
                        updated == null -> "Arrival estimates"
                        stale -> "Stale · ${singaporeTime.format(updated)}"
                        else -> "As of ${singaporeTime.format(updated)}"
                    },
                    LightTextVariant.Detail,
                )
                Action("Refresh", model::refreshArrivals, enabled = !state.arrivalsLoading)
            }
            state.message?.let { Note(it) }
            state.arrivalError?.let { Note(it) }
            state.routeError?.let { Note(it) }
            if (state.routesLoading) Note("Loading the full service list…")
            if (updated != null && services.none { row -> row.times.any { it != null } }) {
                Note("No arrival estimates available right now.")
            }
            if (services.isNotEmpty()) {
                ArrivalRow("Bus", listOf("Next", "2nd", "3rd"), heading = true)
                LazyColumn {
                    items(services, key = { it.number }) { service ->
                        val labels = service.times.map { etaLabel(if (stale) null else it, now) }
                        ArrivalRow(service.number, labels)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrivalRow(number: String, labels: List<String>, heading: Boolean = false) {
    val variant = if (heading) LightTextVariant.Detail else LightTextVariant.Copy
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (heading) 30.dp else 43.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(number, variant, modifier = Modifier.weight(1.15f))
        labels.forEach { label ->
            LightText(label, variant, align = TextAlign.Center, modifier = Modifier.weight(1f))
        }
    }
    Divider()
}

@Composable
private fun StopRow(stop: BusStop, metres: Int?, select: () -> Unit) {
    val details = listOfNotNull(stop.code, stop.road.takeIf { it.isNotBlank() }, metres?.let { "${it}m" })
    Column(
        Modifier.fillMaxWidth().lightClickable(onClick = select).padding(vertical = 10.dp),
    ) {
        LightText(stop.name, LightTextVariant.Copy)
        LightText(details.joinToString(" · "), LightTextVariant.Detail, lighten = true)
    }
    Divider()
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
private fun Action(text: String, perform: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.heightIn(min = 44.dp).lightClickable(enabled = enabled, onClick = perform)
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
