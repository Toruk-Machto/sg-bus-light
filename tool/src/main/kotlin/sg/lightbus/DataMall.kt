package sg.lightbus

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://datamall2.mytransport.sg/ltaodataservice/"
private const val PAGE_SIZE = 500
private const val CACHE_AGE_MS = 7L * 24 * 60 * 60 * 1000
private val dataJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

class DataMallError(message: String, val httpStatus: Int? = null) : IOException(message)

internal fun dataMallHttpError(path: String, status: Int): DataMallError {
    val endpoint = path.substringBefore('?').substringAfterLast('/')
    val skip = path.substringAfter("\$skip=", "").toIntOrNull()
    val request = if (skip == null) endpoint else "$endpoint page ${skip / PAGE_SIZE + 1}"
    val detail = when (status) {
        401 -> "LTA did not authorize the request."
        403 -> "LTA denied access to the request."
        429 -> "LTA is limiting requests. Try again in a minute."
        else -> "LTA data is temporarily unavailable."
    }
    return DataMallError("$request (HTTP $status). $detail", status)
}

@Serializable
private data class DataPage<T>(val value: List<T>)

@Serializable
private data class ArrivalResponse(
    @SerialName("BusStopCode") val stopCode: String,
    @SerialName("Services") val services: List<LiveService>,
)

@Serializable
private data class Catalog<T>(val fetchedAt: Long, val value: T) {
    fun isFresh() = System.currentTimeMillis() - fetchedAt in 0 until CACHE_AGE_MS
}

internal fun parseArrivals(body: String, code: String): List<LiveService> {
    if (body.isBlank()) return emptyList()
    val response = dataJson.decodeFromString<ArrivalResponse>(body)
    if (response.stopCode != code) throw DataMallError("LTA returned a different stop.")
    return response.services
}

class DataMall(private val directory: File) {
    private val stopsFile = AtomicFile(File(directory, "bus-stops.json"))
    private val routeStopsFile = AtomicFile(File(directory, "bus-route-stops.json"))
    private val servicesFile = AtomicFile(File(directory, "bus-services.json"))
    private val stopsLock = Mutex()
    private val routesLock = Mutex()
    private val servicesLock = Mutex()
    private var stopsInMemory: Catalog<List<BusStop>>? = null
    private var routeStopsInMemory: Catalog<List<BusRouteStop>>? = null
    private var servicesInMemory: Catalog<List<BusService>>? = null
    private val individualStops = mutableMapOf<String, BusStop>()

    suspend fun validateKey(key: String): Unit = withContext(Dispatchers.IO) {
        val response = get("BusStops?\$skip=0", key)
        if (dataJson.decodeFromString<DataPage<BusStop>>(response).value.isEmpty()) {
            throw DataMallError("LTA did not return bus stops to verify the key. Try again shortly.")
        }
    }

    suspend fun cachedStops(): List<BusStop> = withContext(Dispatchers.IO) {
        stopsLock.withLock { readStops()?.value.orEmpty() }
    }

    suspend fun cachedRouteStops(): List<BusRouteStop> = withContext(Dispatchers.IO) {
        routesLock.withLock { readRouteStops()?.value.orEmpty() }
    }

    suspend fun cachedServices(): List<BusService> = withContext(Dispatchers.IO) {
        servicesLock.withLock { readServices()?.value.orEmpty() }
    }

    suspend fun isOfflineCopyReady(): Boolean = withContext(Dispatchers.IO) {
        stopsLock.withLock { stopsFile.baseFile.exists() && !readStops()?.value.isNullOrEmpty() } &&
            routesLock.withLock { routeStopsFile.baseFile.exists() && !readRouteStops()?.value.isNullOrEmpty() } &&
            servicesLock.withLock { servicesFile.baseFile.exists() && !readServices()?.value.isNullOrEmpty() }
    }

    suspend fun hasStaleCatalogs(): Boolean = withContext(Dispatchers.IO) {
        stopsLock.withLock { readStops()?.isFresh() == false } ||
            routesLock.withLock { readRouteStops()?.isFresh() == false } ||
            servicesLock.withLock { readServices()?.isFresh() == false }
    }

    suspend fun downloadCatalogs(key: String): Boolean {
        stops(key)
        routeStops(key)
        services(key)
        return isOfflineCopyReady()
    }

    suspend fun stopByCode(code: String, key: String): BusStop? = withContext(Dispatchers.IO) {
        require(isStopCode(code))
        stopsLock.withLock {
            val cachedStop = readStops()?.value?.firstOrNull { it.code == code } ?: individualStops[code]
            if (cachedStop != null) return@withLock cachedStop

            val body = get("BusStops?BusStopCode=$code", key)
            dataJson.decodeFromString<DataPage<BusStop>>(body).value.firstOrNull { it.code == code }
                ?.also { individualStops[code] = it }
        }
    }

    suspend fun stops(key: String): List<BusStop> = withContext(Dispatchers.IO) {
        stopsLock.withLock {
            val result = loadCatalog(readStops(), stopsFile) {
                pages<BusStop>("BusStops", key).distinctBy { it.code }
            }
            stopsInMemory = result
            result.value
        }
    }

    suspend fun routeStops(key: String): List<BusRouteStop> = withContext(Dispatchers.IO) {
        routesLock.withLock {
            val result = loadCatalog(readRouteStops(), routeStopsFile) { pages<BusRouteStop>("BusRoutes", key) }
            routeStopsInMemory = result
            result.value
        }
    }

    suspend fun routes(key: String): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val legacyFile = AtomicFile(File(directory, "bus-routes.json"))
        val legacy = if (cachedRouteStops().isEmpty()) readCache<Map<String, List<String>>>(legacyFile) else null
        if (legacy?.isFresh() == true) return@withContext legacy.value

        val stops = try {
            routeStops(key)
        } catch (error: IOException) {
            return@withContext legacy?.value ?: throw error
        }
        stops.groupBy({ it.stopCode }, { it.service }).mapValues { (_, services) -> services.distinct() }
    }

    suspend fun services(key: String): List<BusService> = withContext(Dispatchers.IO) {
        servicesLock.withLock {
            val result = loadCatalog(readServices(), servicesFile) {
                pages<BusService>("BusServices", key)
            }
            servicesInMemory = result
            result.value
        }
    }

    suspend fun arrivals(code: String, key: String, service: String? = null): List<LiveService> =
        withContext(Dispatchers.IO) {
            require(isStopCode(code))
            require(service == null || (service.isNotBlank() && service.all { it.isLetterOrDigit() }))
            val filter = service?.let { "&ServiceNo=$it" }.orEmpty()
            parseArrivals(get("v3/BusArrival?BusStopCode=$code$filter", key), code)
        }

    private fun readStops(): Catalog<List<BusStop>>? =
        stopsInMemory ?: readCache<List<BusStop>>(stopsFile).also { stopsInMemory = it }

    private fun readRouteStops(): Catalog<List<BusRouteStop>>? =
        routeStopsInMemory ?: readCache<List<BusRouteStop>>(routeStopsFile).also { routeStopsInMemory = it }

    private fun readServices(): Catalog<List<BusService>>? =
        servicesInMemory ?: readCache<List<BusService>>(servicesFile).also { servicesInMemory = it }

    private suspend inline fun <reified T> pages(endpoint: String, key: String): List<T> {
        val all = mutableListOf<T>()
        for (page in 0 until 300) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val batch = dataJson.decodeFromString<DataPage<T>>(
                get("$endpoint?\$skip=${page * PAGE_SIZE}", key),
            ).value
            all.addAll(batch)
            if (batch.size < PAGE_SIZE) {
                if (all.isEmpty()) throw DataMallError("LTA returned an empty stop or route directory.")
                return all
            }
            delay(200)
        }
        throw DataMallError("LTA returned an incomplete directory. Please try again.")
    }

    private fun get(path: String, key: String): String {
        if (key.isBlank()) throw DataMallError("Enter your LTA DataMall key first.")
        val connection = URL(BASE_URL + path).openConnection() as HttpsURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("AccountKey", key)
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != 200) throw dataMallHttpError(path, status)
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private inline fun <reified T> readCache(file: AtomicFile): Catalog<T>? =
        runCatching { dataJson.decodeFromString<Catalog<T>>(file.readFully().decodeToString()) }.getOrNull()

    private suspend inline fun <reified T> loadCatalog(
        cached: Catalog<T>?,
        file: AtomicFile,
        refresh: suspend () -> T,
    ): Catalog<T> {
        if (cached?.isFresh() == true) {
            if (!file.baseFile.exists()) writeCache(cached, file)
            return cached
        }
        val value = try {
            refresh()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return cached ?: throw error
        }
        val catalog = Catalog(System.currentTimeMillis(), value)
        writeCache(catalog, file)
        return catalog
    }

    private inline fun <reified T> writeCache(catalog: Catalog<T>, file: AtomicFile) {
        val bytes = dataJson.encodeToString(catalog).encodeToByteArray()
        val stream = try {
            file.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: IOException) {
            file.failWrite(stream)
        }
    }
}
