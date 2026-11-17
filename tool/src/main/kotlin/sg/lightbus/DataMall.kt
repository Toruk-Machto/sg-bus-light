package sg.lightbus

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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

internal fun retryDelay(error: Exception, previous: Long): Long =
    if ((error as? DataMallError)?.httpStatus in setOf(401, 403)) Long.MAX_VALUE
    else previous.coerceAtMost(150_000L) * 2

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

class DataMall(
    private val directory: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
) {
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

    private suspend inline fun <reified T> pages(endpoint: String, key: String): List<T> =
        withTimeoutOrNull(180_000L) {
            val all = mutableListOf<T>()
            for (page in 0 until 300) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val batch = dataJson.decodeFromString<DataPage<T>>(
                    get("$endpoint?\$skip=${page * PAGE_SIZE}", key),
                ).value
                all.addAll(batch)
                if (batch.size < PAGE_SIZE) {
                    if (all.isEmpty()) throw DataMallError("LTA returned an empty stop or route directory.")
                    return@withTimeoutOrNull all
                }
                delay(200)
            }
            throw DataMallError("LTA returned an incomplete directory. Please try again.")
    } ?: throw DataMallError("The directory download timed out. Try again on a stronger connection.")

    private suspend fun get(path: String, key: String): String {
        if (key.isBlank()) throw DataMallError("Enter your LTA DataMall key first.")
        val request = Request.Builder().url(BASE_URL + path)
            .header("AccountKey", key).header("Accept", "application/json").build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            if (!it.isSuccessful) throw dataMallHttpError(path, it.code)
                            val source = it.body.source()
                            if (source.request(16L * 1024 * 1024 + 1)) {
                                throw DataMallError("LTA returned a response that was too large.")
                            }
                            source.readUtf8()
                        }
                        continuation.resume(body)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            })
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
