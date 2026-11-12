package sg.lightbus

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BusStop(
    @SerialName("BusStopCode") val code: String,
    @SerialName("Description") val name: String,
    @SerialName("RoadName") val road: String,
    @SerialName("Latitude") val latitude: Double,
    @SerialName("Longitude") val longitude: Double,
)

data class NearbyStop(val stop: BusStop, val metres: Int)

@Serializable
data class BusRouteStop(
    @SerialName("BusStopCode") val stopCode: String,
    @SerialName("ServiceNo") val service: String,
)

@Serializable
data class ArrivalPrediction(
    @SerialName("EstimatedArrival") val timestamp: String = "",
)

@Serializable
data class LiveService(
    @SerialName("ServiceNo") val number: String,
    @SerialName("NextBus") val first: ArrivalPrediction? = null,
    @SerialName("NextBus2") val second: ArrivalPrediction? = null,
    @SerialName("NextBus3") val third: ArrivalPrediction? = null,
)

data class ServiceArrivals(val number: String, val times: List<Instant?>)

internal fun isStopCode(text: String): Boolean = text.length == 5 && text.all { it in '0'..'9' }

internal fun findStops(stops: List<BusStop>, query: String): List<BusStop> {
    val phrase = normalize(query)
    if (phrase.isEmpty()) return emptyList()
    val terms = phrase.split(' ')
    return stops.asSequence()
        .filter { stop ->
            val searchText = normalize("${stop.code} ${stop.name} ${stop.road}")
            terms.all(searchText::contains)
        }
        .sortedWith(compareBy<BusStop> {
            when {
                it.code == phrase -> 0
                normalize(it.name) == phrase -> 1
                normalize(it.name).startsWith(phrase) -> 2
                else -> 3
            }
        }.thenBy { it.name }.thenBy { it.code })
        .toList()
}

private val nonSearchCharacters = Regex("[^\\p{L}\\p{N}]+")
private fun normalize(value: String) =
    value.lowercase(Locale.ROOT).replace(nonSearchCharacters, " ").trim()

internal fun nearbyStops(stops: List<BusStop>, latitude: Double, longitude: Double): List<NearbyStop> =
    stops.asSequence()
        .filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        .map { NearbyStop(it, distanceMetres(latitude, longitude, it.latitude, it.longitude)) }
        .filter { it.metres <= 2_000 }
        .sortedWith(compareBy<NearbyStop> { it.metres }.thenBy { it.stop.code })
        .take(20)
        .toList()

private fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val deltaLat = Math.toRadians(lat2 - lat1)
    val deltaLon = Math.toRadians(lon2 - lon1)
    val haversine = sin(deltaLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(deltaLon / 2).let { it * it }
    return (12_742_000 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))).roundToInt()
}

internal fun mergeServices(routes: List<String>, live: List<LiveService>): List<ServiceArrivals> {
    val byNumber = live.groupBy { it.number }
    return (routes + byNumber.keys).filter(String::isNotBlank).distinct()
        .sortedWith(compareBy<String> { number ->
            number.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE
        }.thenBy { it })
        .map { number ->
            val predictions = byNumber[number].orEmpty().distinct()
                .flatMap { listOf(it.first, it.second, it.third) }
                .mapNotNull { prediction -> prediction?.timestamp?.let(::arrivalInstant) }
                .sorted()
            ServiceArrivals(number, List(3) { predictions.getOrNull(it) })
        }
}

private fun arrivalInstant(timestamp: String): Instant? {
    if (timestamp.isBlank()) return null
    return try {
        OffsetDateTime.parse(timestamp).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun etaLabel(arrival: Instant?, now: Instant): String {
    if (arrival == null) return "—"
    val seconds = Duration.between(now, arrival).seconds
    return when {
        seconds < -60 -> "—"
        seconds < 60 -> "Due"
        else -> "${seconds / 60}m"
    }
}
