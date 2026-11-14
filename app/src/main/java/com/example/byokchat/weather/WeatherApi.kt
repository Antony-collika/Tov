package com.example.byokchat.weather

import com.example.byokchat.ai.Http
import com.example.byokchat.ai.executeCancellable
import com.example.byokchat.tools.ToolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Weather via Open-Meteo (2026-11-14). Free, no registration, no API key.
 * Two plain HTTPS GET calls, both returning JSON:
 * 1. Geocoding: geocoding-api.open-meteo.com/v1/search — place name ->
 *    latitude/longitude (first result only).
 * 2. Forecast: api.open-meteo.com/v1/forecast — current weather + daily
 *    forecast for those coordinates.
 *
 * No fallback behind the user's back: a place name with no geocoding
 * result is reported as not found, never silently replaced by another
 * place. A "lat,lon" place skips geocoding entirely.
 */

// ---------- Results ----------

data class GeocodedPlace(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val country: String?,
)

data class CurrentWeather(
    val temperatureC: Double?,
    val feelsLikeC: Double?,
    val humidityPercent: Int?,
    val windKmh: Double?,
    val weatherCode: Int?,
)

data class DailyForecast(
    val date: String,
    val highC: Double?,
    val lowC: Double?,
    val rainProbabilityPercent: Int?,
    val weatherCode: Int?,
)

data class WeatherReport(
    val place: GeocodedPlace,
    val current: CurrentWeather,
    val days: List<DailyForecast>,
)

// ---------- Pure parsing (testable without network) ----------

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

private fun JsonObject.num(key: String): Double? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull

private fun JsonObject.intNum(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

private val LAT_LON = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""")

/**
 * "21.02,105.85" -> (21.02, 105.85); anything else -> null.
 * Out-of-range coordinates are not valid lat/lon either.
 */
fun parseLatLon(place: String): Pair<Double, Double>? {
    val m = LAT_LON.matchEntire(place.trim()) ?: return null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null
    val lon = m.groupValues[2].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}

/** Geocoding response -> first result, or null when the place was not found. */
fun parseGeocoding(root: JsonObject): GeocodedPlace? {
    val first = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val lat = first.num("latitude") ?: return null
    val lon = first.num("longitude") ?: return null
    return GeocodedPlace(
        latitude = lat,
        longitude = lon,
        name = first.str("name") ?: return null,
        country = first.str("country"),
    )
}

/** Forecast response -> current weather + one entry per daily date. */
fun parseForecast(place: GeocodedPlace, root: JsonObject): WeatherReport {
    val currentObj = root["current"] as? JsonObject
    val current = CurrentWeather(
        temperatureC = currentObj?.num("temperature_2m"),
        feelsLikeC = currentObj?.num("apparent_temperature"),
        humidityPercent = currentObj?.intNum("relative_humidity_2m"),
        windKmh = currentObj?.num("wind_speed_10m"),
        weatherCode = currentObj?.intNum("weather_code"),
    )
    val dailyObj = root["daily"] as? JsonObject
    fun doubleArray(key: String): List<Double?> =
        dailyObj?.get(key)?.jsonArray?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull } ?: emptyList()

    fun intArray(key: String): List<Int?> =
        dailyObj?.get(key)?.jsonArray?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull } ?: emptyList()

    val dates = dailyObj?.get("time")?.jsonArray
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull } ?: emptyList()
    val codes = intArray("weather_code")
    val highs = doubleArray("temperature_2m_max")
    val lows = doubleArray("temperature_2m_min")
    val rain = intArray("precipitation_probability_max")
    val days = dates.mapIndexed { i, date ->
        DailyForecast(
            date = date,
            highC = highs.getOrNull(i),
            lowC = lows.getOrNull(i),
            rainProbabilityPercent = rain.getOrNull(i),
            weatherCode = codes.getOrNull(i),
        )
    }
    return WeatherReport(place, current, days)
}

/** WMO weather code -> short plain description. */
fun describeWeatherCode(code: Int?): String = when (code) {
    null -> "unknown conditions"
    0 -> "clear sky"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61 -> "light rain"
    63 -> "rain"
    65 -> "heavy rain"
    66, 67 -> "freezing rain"
    71 -> "light snow"
    73 -> "snow"
    75 -> "heavy snow"
    77 -> "snow grains"
    80 -> "light rain showers"
    81 -> "rain showers"
    82 -> "violent rain showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "thunderstorm with hail"
    else -> "unknown conditions"
}

// ---------- Model-facing text ----------

private fun fmtTemp(c: Double?): String =
    c?.let { if (it == kotlin.math.floor(it)) "${it.toInt()}°C" else "%.1f°C".format(it) } ?: "—"

private fun fmtWind(kmh: Double?): String =
    kmh?.let { if (it == kotlin.math.floor(it)) "${it.toInt()} km/h" else "%.1f km/h".format(it) } ?: "—"

fun formatWeather(report: WeatherReport): String {
    val place = report.place
    val where = place.name + (place.country?.let { ", $it" } ?: "")
    val lines = mutableListOf<String>()
    lines += "Weather in $where:"
    val c = report.current
    lines += "Now: ${fmtTemp(c.temperatureC)} (feels like ${fmtTemp(c.feelsLikeC)}), " +
        "${describeWeatherCode(c.weatherCode)}, " +
        "humidity ${c.humidityPercent?.let { "$it%" } ?: "—"}, " +
        "wind ${fmtWind(c.windKmh)}."
    if (report.days.isEmpty()) {
        lines += "No forecast days were returned."
    } else {
        lines += "Forecast:"
        for (d in report.days) {
            lines += "• ${d.date}: ${fmtTemp(d.highC)} / ${fmtTemp(d.lowC)}, " +
                "${describeWeatherCode(d.weatherCode)}, " +
                "rain ${d.rainProbabilityPercent?.let { "$it%" } ?: "—"}."
        }
    }
    return lines.joinToString("\n")
}

// ---------- HTTP client ----------

class OpenMeteoApi(
    private val http: OkHttpClient = Http.fetchClient,
) {
    private suspend fun get(url: okhttp3.HttpUrl, what: String): JsonObject {
        val req = Request.Builder().url(url).get().build()
        try {
            return http.executeCancellable(req) { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code !in 200..299) throw ToolException(mapApiError(resp.code, what))
                runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { throw ToolException("Open-Meteo returned a response that could not be read. Try again.") }
            }
        } catch (e: IOException) {
            throw ToolException("Could not reach Open-Meteo: ${e.message}. Check the network and try again.")
        }
    }

    fun mapApiError(code: Int, what: String): String = when (code) {
        400 -> "Open-Meteo rejected the request (400) for $what. The place or the requested days may be invalid."
        429 -> "Open-Meteo is rate-limiting requests (429) for $what. Wait a moment and try again."
        in 500..599 -> "Open-Meteo's server is having problems ($code) for $what. Try again in a few minutes."
        else -> "Open-Meteo returned error $code for $what. Try again."
    }

    /** Place name -> coordinates (first geocoding result). Null when not found. */
    suspend fun geocode(name: String): GeocodedPlace? {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .addQueryParameter("count", "1")
            .addQueryParameter("language", "vi")
            .addQueryParameter("format", "json")
            .build()
        return parseGeocoding(get(url, "place lookup"))
    }

    suspend fun forecastFor(place: GeocodedPlace, days: Int): WeatherReport {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", place.latitude.toString())
            .addQueryParameter("longitude", place.longitude.toString())
            .addQueryParameter("current", "temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", days.coerceIn(1, 7).toString())
            .build()
        return parseForecast(place, get(url, "forecast"))
    }

    /**
     * Full flow for one tool call: "lat,lon" skips geocoding; a place name
     * is looked up first and a miss is reported clearly, never guessed.
     */
    suspend fun forecast(place: String, days: Int): String {
        val trimmed = place.trim()
        if (trimmed.isEmpty()) throw ToolException("Give a place name, e.g. 'Ha Noi', or coordinates like '21.02,105.85'.")
        val coords = parseLatLon(trimmed)
        val resolved = if (coords != null) {
            GeocodedPlace(coords.first, coords.second, trimmed, null)
        } else {
            geocode(trimmed)
                ?: throw ToolException(
                    "No place named '$trimmed' was found. Check the spelling and try again — " +
                        "or give coordinates instead, e.g. '21.02,105.85'.",
                )
        }
        return formatWeather(forecastFor(resolved, days))
    }
}
