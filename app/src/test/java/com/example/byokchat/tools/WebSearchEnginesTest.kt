package com.example.byokchat.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test cho web search engines (DuckDuckGo + Serper, 2026-11-14): chỉ test
 *  parser thuần (sample HTML/JSON), không gọi mạng thật. */
class WebSearchEnginesTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    // ----- Serper -----

    private val serperSample = """
        {
          "organic": [
            {"title": "Kotlin docs", "link": "https://kotlinlang.org/", "snippet": "Kotlin is a programming language."},
            {"title": "No snippet here", "link": "https://example.com/no-snippet"},
            {"title": "Third result", "link": "https://example.com/third", "snippet": "Third snippet."}
          ],
          "searchParameters": {"q": "kotlin", "type": "search"}
        }
    """.trimIndent()

    @Test
    fun serperParsesOrganicTitleLinkSnippet() {
        val results = parseSerperResults(json(serperSample))
        assertEquals(3, results.size)
        assertEquals(WebSearchResult("Kotlin docs", "https://kotlinlang.org/", "Kotlin is a programming language."), results[0])
        assertEquals("", results[1].snippet)
        assertEquals("Third result", results[2].title)
    }

    @Test
    fun serperEmptyOrganicGivesEmptyList() {
        assertTrue(parseSerperResults(json("""{"organic": []}""")).isEmpty())
        assertTrue(parseSerperResults(json("""{}""")).isEmpty())
    }

    // ----- DuckDuckGo (html endpoint) -----

    private val ddgSample = """
        <div class="result results_links results_links_deep web-result ">
          <div class="links_main links_deep result__body">
            <h2 class="result__title">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&amp;rut=abc123">Kotlin <b>docs</b></a>
            </h2>
            <a class="result__snippet" href="https://kotlinlang.org/">Kotlin is a programming language &amp; runs on the JVM.</a>
          </div>
        </div>
        <div class="result results_links results_links_deep web-result ">
          <div class="links_main links_deep result__body">
            <h2 class="result__title">
              <a rel="nofollow" class="result__a" href="https://example.com/direct">Direct link result</a>
            </h2>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun ddgUnwrapsUddgRedirectAndDecodesEntities() {
        val results = parseDuckDuckGoResults(ddgSample)
        assertEquals(2, results.size)
        assertEquals("Kotlin docs", results[0].title)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("Kotlin is a programming language & runs on the JVM.", results[0].snippet)
    }

    @Test
    fun ddgKeepsDirectHrefAndEmptySnippetWhenMissing() {
        val results = parseDuckDuckGoResults(ddgSample)
        assertEquals("https://example.com/direct", results[1].url)
        assertEquals("", results[1].snippet)
    }

    @Test
    fun ddgEmptyPageGivesEmptyList() {
        assertTrue(parseDuckDuckGoResults("<html><body>No results.</body></html>").isEmpty())
    }

    // ----- Các engine cũ vẫn parse như trước -----

    @Test
    fun braveParsesWebResultsAndStripsHtmlFromDescription() {
        val root = json("""
            {"web": {"results": [
              {"title": "A", "url": "https://a.example/", "description": "Hello <b>world</b> &amp; friends."}
            ]}}
        """.trimIndent())
        val results = parseBraveResults(root)
        assertEquals(1, results.size)
        assertEquals(WebSearchResult("A", "https://a.example/", "Hello world & friends."), results[0])
    }

    @Test
    fun tavilyParsesResultsContent() {
        val root = json("""
            {"results": [{"title": "T", "url": "https://t.example/", "content": "Some content."}]}
        """.trimIndent())
        assertEquals(
            listOf(WebSearchResult("T", "https://t.example/", "Some content.")),
            parseTavilyResults(root),
        )
    }

    // ----- Định dạng chung -----

    @Test
    fun formatResultsUsesBulletTitleUrlSnippet() {
        val out = formatWebSearchResults("kotlin", listOf(
            WebSearchResult("Kotlin docs", "https://kotlinlang.org/", "Kotlin is a programming language."),
        ))
        assertEquals(
            "Search results for 'kotlin':\n• Kotlin docs\n  https://kotlinlang.org/\n  Kotlin is a programming language.",
            out,
        )
    }

    @Test
    fun formatEmptyResultsSaysNoResults() {
        assertEquals("No results for 'kotlin'.", formatWebSearchResults("kotlin", emptyList()))
    }
}
