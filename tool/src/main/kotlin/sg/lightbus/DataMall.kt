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
private val dataJson = Json { ignoreUnknownKeys = true }

class DataMallError(message: String) : IOException(message)

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
    return DataMallError("$request (HTTP $status). $detail")
}

@Serializable
private data class DataPage<T>(val value: List<T>)

@Serializable
private data class ArrivalResponse(
    @SerialName("BusStopCode") val stopCode: String,
    @SerialName("Services") val services: List<LiveService>,
)

@Serializable
private data class Catalog<T>(val fetchedAt: Long, val value: T)

internal fun parseArrivals(body: String, code: String): List<LiveService> {
    if (body.isBlank()) return emptyList()
    val response = dataJson.decodeFromString<ArrivalResponse>(body)
    if (response.stopCode != code) throw DataMallError("LTA returned a different stop.")
    return response.services
}

class DataMall(private val directory: File) {
    private val stopsLock = Mutex()
    private val routesLock = Mutex()
    private var stopsInMemory: Catalog<List<BusStop>>? = null
    private var routesInMemory: Catalog<Map<String, List<String>>>? = null

    suspend fun stopByCode(code: String, key: String): BusStop? = withContext(Dispatchers.IO) {
        require(isStopCode(code))
        stopsLock.withLock {
            val file = AtomicFile(File(directory, "bus-stops.json"))
            val cached = stopsInMemory ?: readCache<List<BusStop>>(file)
            stopsInMemory = cached
            val cachedStop = cached?.value?.firstOrNull { it.code == code }
            if (cachedStop != null) return@withLock cachedStop

            val body = get("BusStops?BusStopCode=$code", key)
            dataJson.decodeFromString<DataPage<BusStop>>(body).value.firstOrNull { it.code == code }
        }
    }

    suspend fun stops(key: String): List<BusStop> = withContext(Dispatchers.IO) {
        stopsLock.withLock {
            val file = AtomicFile(File(directory, "bus-stops.json"))
            val cached = stopsInMemory ?: readCache<List<BusStop>>(file)
            val result = loadCatalog(cached, file) {
                pages<BusStop>("BusStops", key).distinctBy { it.code }
            }
            stopsInMemory = result
            result.value
        }
    }

    suspend fun routes(key: String): Map<String, List<String>> = withContext(Dispatchers.IO) {
        routesLock.withLock {
            val file = AtomicFile(File(directory, "bus-routes.json"))
            val cached = routesInMemory ?: readCache<Map<String, List<String>>>(file)
            val result = loadCatalog(cached, file) {
                pages<BusRouteStop>("BusRoutes", key)
                    .groupBy({ it.stopCode }, { it.service })
                    .mapValues { (_, services) -> services.distinct() }
            }
            routesInMemory = result
            result.value
        }
    }

    suspend fun arrivals(code: String, key: String): List<LiveService> = withContext(Dispatchers.IO) {
        require(isStopCode(code))
        parseArrivals(get("v3/BusArrival?BusStopCode=$code", key), code)
    }

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
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt in 0 until CACHE_AGE_MS) {
            return cached
        }
        val value = try {
            refresh()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return cached ?: throw error
        }
        val catalog = Catalog(System.currentTimeMillis(), value)
        val bytes = dataJson.encodeToString(catalog).encodeToByteArray()
        val stream = try {
            file.startWrite()
        } catch (_: IOException) {
            return catalog
        }
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: IOException) {
            file.failWrite(stream)
        }
        return catalog
    }
}
