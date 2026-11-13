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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

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
    @SerialName("Direction") val direction: Int = 1,
    @SerialName("StopSequence") val sequence: Int = 0,
    @SerialName("Distance") val distance: Double? = null,
    @SerialName("Operator") val operator: String = "",
    @SerialName("WD_FirstBus") val weekdayFirst: String = "",
    @SerialName("WD_LastBus") val weekdayLast: String = "",
    @SerialName("SAT_FirstBus") val saturdayFirst: String = "",
    @SerialName("SAT_LastBus") val saturdayLast: String = "",
    @SerialName("SUN_FirstBus") val sundayFirst: String = "",
    @SerialName("SUN_LastBus") val sundayLast: String = "",
)

@Serializable
data class BusService(
    @SerialName("ServiceNo") val number: String,
    @SerialName("Direction") val direction: Int,
    @SerialName("Operator") val operator: String = "",
    @SerialName("Category") val category: String = "",
    @SerialName("OriginCode") val originCode: String = "",
    @SerialName("DestinationCode") val destinationCode: String = "",
    @SerialName("LoopDesc") val loopDescription: String = "",
    @SerialName("AM_Peak_Freq") val amPeak: String = "",
    @SerialName("AM_Offpeak_Freq") val amOffpeak: String = "",
    @SerialName("PM_Peak_Freq") val pmPeak: String = "",
    @SerialName("PM_Offpeak_Freq") val pmOffpeak: String = "",
)

@Serializable
data class ArrivalPrediction(
    @SerialName("EstimatedArrival") val timestamp: String = "",
    @SerialName("OriginCode") val originCode: String = "",
    @SerialName("DestinationCode") val destinationCode: String = "",
    @SerialName("Load") val load: String = "",
    @SerialName("Type") val vehicleType: String = "",
    @SerialName("Monitored") @Serializable(with = OptionalArrivalInt::class) val monitored: Int? = null,
    @SerialName("VisitNumber") @Serializable(with = OptionalArrivalInt::class) val visitNumber: Int? = null,
) {
    val scheduled: Boolean get() = monitored == 0
    val crowdingLabel: String?
        get() = when (load) {
            "SEA" -> "Seats"
            "SDA" -> "Standing"
            "LSD" -> "Limited standing"
            else -> null
        }
}

// DataMall can supply a number, a quoted number, or a blank for these arrival fields.
private object OptionalArrivalInt : KSerializer<Int?> {
    override val descriptor = PrimitiveSerialDescriptor("DataMallArrivalInt", PrimitiveKind.INT).nullable

    override fun deserialize(decoder: Decoder): Int? =
        ((decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive)?.intOrNull

    override fun serialize(encoder: Encoder, value: Int?) {
        (encoder as JsonEncoder).encodeJsonElement(value?.let(::JsonPrimitive) ?: JsonNull)
    }
}

@Serializable
data class LiveService(
    @SerialName("ServiceNo") val number: String,
    @SerialName("NextBus") val first: ArrivalPrediction? = null,
    @SerialName("NextBus2") val second: ArrivalPrediction? = null,
    @SerialName("NextBus3") val third: ArrivalPrediction? = null,
)

data class ServiceArrivals(
    val number: String,
    val times: List<Instant?>,
    val predictions: List<ArrivalPrediction?> = emptyList(),
)

data class DirectBus(
    val service: String,
    val direction: Int,
    val board: BusRouteStop,
    val alight: BusRouteStop,
)

private val serviceOrder = compareBy<String> { it.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
    .thenBy { it }

internal fun directBuses(routes: List<BusRouteStop>, originCode: String, destinationCode: String): List<DirectBus> {
    if (originCode == destinationCode) return emptyList()
    return routes.asSequence()
        .filter { it.stopCode == originCode || it.stopCode == destinationCode }
        .groupBy { it.service to it.direction }
        .mapNotNull { (identity, stops) ->
            val (service, direction) = identity
            val origins = stops.filter { it.stopCode == originCode }
            val destinations = stops.filter { it.stopCode == destinationCode }
            origins.asSequence()
                .flatMap { board -> destinations.asSequence()
                    .filter { it.sequence > board.sequence }
                    .map { DirectBus(service, direction, board, it) }
                }
                .minByOrNull { it.alight.sequence - it.board.sequence }
        }
        .sortedWith(compareBy<DirectBus, String>(serviceOrder) { it.service }.thenBy { it.direction })
}

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
        .sortedWith(serviceOrder)
        .map { number ->
            val predictions = byNumber[number].orEmpty().distinct()
                .flatMap { listOf(it.first, it.second, it.third) }
                .filterNotNull()
                .mapNotNull { prediction -> arrivalInstant(prediction.timestamp)?.let { it to prediction } }
                .sortedBy { it.first }
            ServiceArrivals(
                number,
                List(3) { predictions.getOrNull(it)?.first },
                List(3) { predictions.getOrNull(it)?.second },
            )
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
