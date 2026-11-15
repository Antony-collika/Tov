package com.example.byokchat.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val geocodingJson = """{"results":[
        {"id":1581130,"name":"Hanoi","latitude":21.0245,"longitude":105.84117,
         "country_code":"VN","admin1":"Hà Nội","country":"Vietnam","timezone":"Asia/Bangkok"}
    ]}"""

    private val forecastJson = """{"latitude":21.02,"longitude":105.84,
        "timezone":"Asia/Bangkok",
        "current":{"time":"2026-11-14T10:00","temperature_2m":28.4,
          "relative_humidity_2m":72,"apparent_temperature":31.2,
          "weather_code":2,"wind_speed_10m":9.5},
        "daily":{"time":["2026-11-14","2026-11-15","2026-11-16"],
          "weather_code":[2,61,0],
          "temperature_2m_max":[30.1,29.0,31.0],
          "temperature_2m_min":[22.5,23.0,22.0],
          "precipitation_probability_max":[10,80,0]}}"""

    @Test
    fun parseGeocodingTakesFirstResult() {
        val place = parseGeocoding(json.parseToJsonElement(geocodingJson).jsonObject)
        assertEquals("Hanoi", place?.name)
        assertEquals("Vietnam", place?.country)
        assertEquals(21.0245, place?.latitude)
        assertEquals(105.84117, place?.longitude)
    }

    @Test
    fun parseGeocodingReturnsNullWhenPlaceNotFound() {
        val empty = parseGeocoding(json.parseToJsonElement("""{"results":[]}""").jsonObject)
        assertNull(empty)
        val missing = parseGeocoding(json.parseToJsonElement("""{}""").jsonObject)
        assertNull(missing)
    }

    @Test
    fun parseLatLonAcceptsCoordinatesAndRejectsNames() {
        assertEquals(21.02 to 105.85, parseLatLon("21.02,105.85"))
        assertEquals(21.02 to 105.85, parseLatLon("  21.02 , 105.85  "))
        assertEquals((-33.86 to 151.20), parseLatLon("-33.86,151.20"))
        assertNull(parseLatLon("Ha Noi"))
        assertNull(parseLatLon("21.02"))
        assertNull(parseLatLon("21.02,105.85,3"))
        // Out of range is not a valid lat/lon pair.
        assertNull(parseLatLon("91,0"))
        assertNull(parseLatLon("0,181"))
    }

    @Test
    fun parseForecastReadsCurrentAndDaily() {
        val place = GeocodedPlace(21.0245, 105.84117, "Hanoi", "Vietnam")
        val report = parseForecast(place, json.parseToJsonElement(forecastJson).jsonObject)
        assertEquals(28.4, report.current.temperatureC)
        assertEquals(31.2, report.current.feelsLikeC)
        assertEquals(72, report.current.humidityPercent)
        assertEquals(9.5, report.current.windKmh)
        assertEquals(2, report.current.weatherCode)
        assertEquals(3, report.days.size)
        assertEquals("2026-11-15", report.days[1].date)
        assertEquals(29.0, report.days[1].highC)
        assertEquals(23.0, report.days[1].lowC)
        assertEquals(80, report.days[1].rainProbabilityPercent)
        assertEquals(61, report.days[1].weatherCode)
    }

    @Test
    fun describeWeatherCodeCoversEveryGroup() {
        assertEquals("clear sky", describeWeatherCode(0))
        assertEquals("mainly clear", describeWeatherCode(1))
        assertEquals("partly cloudy", describeWeatherCode(2))
        assertEquals("overcast", describeWeatherCode(3))
        assertEquals("fog", describeWeatherCode(45))
        assertEquals("fog", describeWeatherCode(48))
        assertEquals("drizzle", describeWeatherCode(51))
        assertEquals("freezing drizzle", describeWeatherCode(57))
        assertEquals("light rain", describeWeatherCode(61))
        assertEquals("heavy rain", describeWeatherCode(65))
        assertEquals("freezing rain", describeWeatherCode(67))
        assertEquals("light snow", describeWeatherCode(71))
        assertEquals("snow grains", describeWeatherCode(77))
        assertEquals("light rain showers", describeWeatherCode(80))
        assertEquals("violent rain showers", describeWeatherCode(82))
        assertEquals("snow showers", describeWeatherCode(85))
        assertEquals("thunderstorm", describeWeatherCode(95))
        assertEquals("thunderstorm with hail", describeWeatherCode(99))
        assertEquals("unknown conditions", describeWeatherCode(999))
        assertEquals("unknown conditions", describeWeatherCode(null))
    }

    @Test
    fun formatWeatherIsPlainLanguageWithUnits() {
        val place = GeocodedPlace(21.0245, 105.84117, "Hanoi", "Vietnam")
        val report = parseForecast(place, json.parseToJsonElement(forecastJson).jsonObject)
        val text = formatWeather(report)
        assertTrue(text.contains("Weather in Hanoi, Vietnam:"))
        assertTrue(text.contains("28.4°C"))
        assertTrue(text.contains("feels like 31.2°C"))
        assertTrue(text.contains("partly cloudy"))
        assertTrue(text.contains("humidity 72%"))
        assertTrue(text.contains("wind 9.5 km/h"))
        assertTrue(text.contains("2026-11-15: 29°C / 23°C, light rain, rain 80%."))
        assertTrue(text.contains("2026-11-16: 31°C / 22°C, clear sky, rain 0%."))
    }

    @Test
    fun mapApiErrorNamesRateLimitAndServerErrors() {
        val api = OpenMeteoApi()
        assertTrue(api.mapApiError(429, "forecast").contains("429"))
        assertTrue(api.mapApiError(400, "place lookup").contains("400"))
        assertTrue(api.mapApiError(500, "forecast").contains("500"))
    }
}
