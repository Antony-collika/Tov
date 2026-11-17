package com.example.byokchat.ui

import com.example.byokchat.data.ProviderConfig
import com.example.byokchat.data.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the model-picker bottom sheet on the chat screen (17/11/2026,
 * mockup "Model picker mockup"): provider filter chips, collapsible
 * provider groups, search matching provider names, and every model row
 * showing its provider — so same-named models from different providers
 * can be told apart.
 */
class ModelPickerSheetTest {

    private fun provider(
        id: String,
        name: String,
        models: List<String>,
        pinned: List<String> = emptyList(),
    ) = ProviderConfig(
        id = id,
        type = ProviderType.CUSTOM,
        name = name,
        models = models,
        pinnedModels = pinned,
    )

    private val openAi = provider("openai", "OpenAI", listOf("gpt-6.1-terra", "gpt-6.1-sol-fast"), listOf("gpt-6.1-terra"))
    private val azure = provider("azure", "Azure OpenAI", listOf("gpt-6.1-terra", "gpt-4o"))
    private val gemini = provider("gemini", "Gemini", listOf("gemini-2.5-pro"))
    private val providers = listOf(openAi, azure, gemini)

    @Test
    fun noFilterShowsAllGroupsInOrder() {
        val groups = visibleModelGroups(providers, "", null)
        assertEquals(listOf("openai", "azure", "gemini"), groups.map { it.providerId })
        assertEquals(listOf("gpt-6.1-terra", "gpt-6.1-sol-fast"), groups[0].models)
        assertEquals(2, groups[0].totalModels)
        assertEquals(1, groups[2].totalModels)
    }

    @Test
    fun chipFilterShowsOnlyThatProvider() {
        val groups = visibleModelGroups(providers, "", "azure")
        assertEquals(listOf("azure"), groups.map { it.providerId })
        assertEquals(listOf("gpt-6.1-terra", "gpt-4o"), groups[0].models)
    }

    @Test
    fun searchMatchesModelNameAcrossProviders() {
        // Same-named model offered by two providers: it must show up under
        // both groups, each row carrying its own provider name.
        val groups = visibleModelGroups(providers, "terra", null)
        assertEquals(listOf("openai", "azure"), groups.map { it.providerId })
        assertEquals(listOf("gpt-6.1-terra"), groups[0].models)
        assertEquals(listOf("gpt-6.1-terra"), groups[1].models)
        assertEquals("OpenAI", groups[0].providerName)
        assertEquals("Azure OpenAI", groups[1].providerName)
    }

    @Test
    fun searchMatchesProviderNameShowsAllItsModels() {
        val groups = visibleModelGroups(providers, "azure", null)
        assertEquals(listOf("azure"), groups.map { it.providerId })
        assertEquals(listOf("gpt-6.1-terra", "gpt-4o"), groups[0].models)
    }

    @Test
    fun searchMatchesProviderNameCaseInsensitively() {
        val groups = visibleModelGroups(providers, "GEMINI", null)
        assertEquals(listOf("gemini"), groups.map { it.providerId })
        assertEquals(listOf("gemini-2.5-pro"), groups[0].models)
    }

    @Test
    fun searchHidesGroupsWithNoMatch() {
        assertTrue(visibleModelGroups(providers, "zzz-no-such-model", null).isEmpty())
    }

    @Test
    fun emptyProviderShownWhenNotSearchingHiddenWhenSearching() {
        val empty = provider("empty", "Empty", emptyList())
        val all = providers + empty
        val noQuery = visibleModelGroups(all, "", null)
        assertEquals(4, noQuery.size)
        assertTrue(noQuery.last().models.isEmpty())
        val searching = visibleModelGroups(all, "gpt", null)
        assertEquals(listOf("openai", "azure"), searching.map { it.providerId })
    }

    @Test
    fun chipFilterAndSearchCombine() {
        val groups = visibleModelGroups(providers, "terra", "azure")
        assertEquals(listOf("azure"), groups.map { it.providerId })
        assertEquals(listOf("gpt-6.1-terra"), groups[0].models)
    }

    @Test
    fun defaultCollapsedKeepsCurrentProviderOpen() {
        assertEquals(setOf("openai", "gemini"), defaultCollapsedProviders(providers, "azure"))
    }

    @Test
    fun defaultCollapsedWithUnknownCurrentCollapsesAll() {
        // Agent settings with "Follow Main's model": no current provider.
        assertEquals(setOf("openai", "azure", "gemini"), defaultCollapsedProviders(providers, ""))
    }

    @Test
    fun searchForcesGroupsExpanded() {
        assertTrue(isGroupExpanded("openai", "terra", setOf("openai")))
        assertFalse(isGroupExpanded("openai", "", setOf("openai")))
        assertTrue(isGroupExpanded("azure", "", setOf("openai")))
        assertTrue(isGroupExpanded("azure", "  ", setOf("azure", "gemini")))
    }

    @Test
    fun pinnedRespectsChipFilter() {
        val all = visiblePinnedModels(providers, "", null)
        assertEquals(listOf("openai" to "gpt-6.1-terra"), all)
        assertTrue(visiblePinnedModels(providers, "", "azure").isEmpty())
    }

    @Test
    fun pinnedSearchMatchesProviderName() {
        val pinned = provider("p", "Bedrock", listOf("a", "b"), listOf("a", "b"))
        assertEquals(
            listOf("p" to "a", "p" to "b"),
            visiblePinnedModels(listOf(pinned), "bedrock", null),
        )
        assertEquals(
            listOf("p" to "a"),
            visiblePinnedModels(listOf(pinned), "a", null),
        )
        assertTrue(visiblePinnedModels(listOf(pinned), "zzz", null).isEmpty())
    }

    @Test
    fun groupCarriesProviderNameAndPinnedModelsForRowSubtitle() {
        // The row shows the provider name under the model name, so the
        // group must carry both the name and which of its models are pinned.
        val groups = visibleModelGroups(providers, "", null)
        assertEquals("OpenAI", groups[0].providerName)
        assertEquals(listOf("gpt-6.1-terra"), groups[0].pinnedModels)
        assertTrue(groups[1].pinnedModels.isEmpty())
    }
}
