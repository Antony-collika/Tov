package com.example.byokchat.tools

import com.example.byokchat.weather.OpenMeteoApi
import kotlinx.serialization.json.JsonObject

/**
 * Weather tool (2026-11-14). One tool only:
 * weather_forecast — current weather + forecast from Open-Meteo
 * (free, no API key, no sign-up, no Settings entry).
 *
 * Deliberately a separate file from DailyTools.kt: two other agents are
 * working in this repo at the same time, so this tool lives on its own
 * and is registered by adding WeatherTools.build() to ToolRegistry.all.
 */
object WeatherTools {
    fun build(): List<Tool> = listOf(
        Tool(
            uid = "weather_forecast",
            group = ToolGroup.DAILY,
            risk = Risk.READ,
            defaultName = "weather_forecast",
            defaultDescription = "Get the current weather and a forecast for a place, from Open-Meteo (free, no API key or sign-up needed). Give a place name, e.g. 'Ha Noi' or 'Da Lat', or coordinates as 'lat,lon', e.g. '21.02,105.85'. Returns the current temperature, feels-like temperature, humidity, wind speed and condition, plus one line per day with the high/low temperature, rain probability and condition. Default 3 days, at most 7. If the place name is not found, the tool says so clearly instead of guessing another place.",
            schema = schema {
                str("place", "Place name, e.g. 'Ha Noi' or 'Da Lat', or coordinates as 'lat,lon', e.g. '21.02,105.85'.", required = true)
                int("days", "Forecast days, 1-7, default 3.")
            },
            previewFn = { _, a ->
                "Weather for ${a.reqStr("place")}" +
                    (a.int("days")?.let { " ($it days)" } ?: "")
            },
            run = { _, a ->
                val place = a.reqStr("place").trim()
                val days = (a.int("days") ?: 3).coerceIn(1, 7)
                OpenMeteoApi().forecast(place, days)
            },
        ),
    )
}
