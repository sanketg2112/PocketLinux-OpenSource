package com.sg.linuxgo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetSuggestionModelsTest {

    private val snippets = listOf(
        SnippetEntity(id = "1", title = "Disk Usage", command = "df -h"),
        SnippetEntity(id = "2", title = "List Files", command = "ls -la"),
        SnippetEntity(id = "3", title = "Update Packages", command = "apt update && apt upgrade -y"),
        SnippetEntity(id = "4", title = "Apt Search", command = "apt search"),
        SnippetEntity(id = "5", title = "Docker PS", command = "docker ps")
    )

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(matchSnippetSuggestions("", snippets).isEmpty())
        assertTrue(matchSnippetSuggestions("   ", snippets).isEmpty())
    }

    @Test
    fun matchesCommandPrefixPreferringCommandsOverTitles() {
        val hits = matchSnippetSuggestions("apt", snippets)
        assertEquals(2, hits.size)
        // command startsWith ranks before title-only
        assertEquals("apt search", hits[0].command)
        assertEquals("apt update && apt upgrade -y", hits[1].command)
    }

    @Test
    fun matchesTitlePrefix() {
        val hits = matchSnippetSuggestions("Disk", snippets)
        assertEquals(listOf("df -h"), hits.map { it.command })
    }

    @Test
    fun excludesExactCommandMatchSoEnterCanExecute() {
        val hits = matchSnippetSuggestions("df -h", snippets)
        assertTrue(hits.none { it.command.equals("df -h", ignoreCase = true) })
    }

    @Test
    fun respectsLimitAndCaseInsensitivity() {
        val many = (1..20).map {
            SnippetEntity(id = "$it", title = "Cmd$it", command = "cmd$it")
        }
        assertEquals(3, matchSnippetSuggestions("cmd", many, limit = 3).size)
        assertEquals(1, matchSnippetSuggestions("DOCKER", snippets).size)
    }

    @Test
    fun nonPositiveLimitReturnsEmpty() {
        assertTrue(matchSnippetSuggestions("a", snippets, limit = 0).isEmpty())
    }
}
