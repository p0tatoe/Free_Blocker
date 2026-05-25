package dev.michaelylee.freeblocker.data

import android.util.Log
import dev.michaelylee.freeblocker.core.DnsFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

sealed interface BlocklistState {
    object Idle : BlocklistState
    object Loading : BlocklistState
    data class Success(val totalDomains: Int) : BlocklistState
    data class Error(val message: String) : BlocklistState
}

data class FilterSource(
    val url: String,
    val enabled: Boolean = true
)

interface SourceProvider {
    fun getSources(): List<FilterSource>
}

class DefaultSourceProvider : SourceProvider {
    override fun getSources(): List<FilterSource> = listOf(
        FilterSource("https://pgl.yoyo.org/adservers/serverlist.php"),
        FilterSource("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
        FilterSource("https://big.oisd.nl/domainswild")
    )
}

class FilterParser {
    /**
     * Normalizes blocking rules: Adblock syntax (`||badsite.com^`) etc.
     * Returns null for comment lines, empty lines, and invalid entries.
     */
    fun parse(rawLine: String): String? {
        var line = rawLine.trim()

        // Strip inline comments
        if (line.contains("#")) line = line.substringBefore("#").trim()
        if (line.contains(";")) line = line.substringBefore(";").trim()

        if (line.isEmpty()) return null

        // Support popular AdBlock/uBlock style simple wildcard domains: ||example.com^
        line = when {
            line.startsWith("||") && line.endsWith("^") -> line.substring(2, line.length - 1)
            line.startsWith("||")                              -> line.substring(2)
            else                                                      -> line
        }

        // Clean up leading wildcard formatting indicators
        if (line.startsWith("*.")) line = line.substring(2)

        // Split tokens to break up standard hosts records (IP <space> Host)
        val segments = line.split(Regex("\\s+"))
        val candidate = if (segments.size >= 2) segments[1] else segments[0]

        val cleaned = candidate.lowercase(Locale.ROOT).trim()
        return if (isValid(cleaned)) cleaned else null
    }

    private fun isValid(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (domain == "localhost" || domain == "127.0.0.1" || domain == "0.0.0.0") return false
        return domain.contains(".") && !domain.startsWith(".") && !domain.endsWith(".")
    }
}

class BlocklistFetcher {
    private val parser: FilterParser = FilterParser()
    suspend fun fetch(url: String): Set<String> = withContext(Dispatchers.IO) {
        val result = HashSet<String>()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.inputStream.bufferedReader().useLines { lines ->
            lines.forEach {
                val parsed = parser.parse(it)
                if (parsed != null) result.add(parsed)
            }
        }
        result
    }
}

class BlocklistRepository(
    private val dnsFilter: DnsFilter,
    private val userPreferences: UserPreferences,
    private val fetcher: BlocklistFetcher,
    private val sourceProvider: SourceProvider = DefaultSourceProvider()
) {
    private val TAG = "BlocklistRepository"

    private val _state = MutableStateFlow<BlocklistState>(BlocklistState.Idle)
    val state: StateFlow<BlocklistState> = _state.asStateFlow()

    /**
     * Populates DnsFilter with blocklists, user rules, and whitelist rules
     */
    suspend fun loadAndCompileBlocklists() = withContext(Dispatchers.IO) {

        _state.value = BlocklistState.Loading

        try {
            val compiled = HashSet<String>(250_000)
            val customUrls = userPreferences.getCustomSourceUrls()
            val allSources = sourceProvider.getSources() + customUrls.map { FilterSource(it) }

            coroutineScope {
                allSources
                    .filter { it.enabled }
                    .map { source -> async { fetcher.fetch(source.url) } }
                    .awaitAll()
                    .forEach { compiled.addAll(it) }
            }

            compiled.addAll(
                userPreferences.getManualBlockedDomains().map { it.lowercase() }
            )

            compiled.removeAll(
                userPreferences.getWhitelistedDomains().map { it.lowercase() }.toSet()
            )

            dnsFilter.updateBlocklist(compiled)

            _state.value = BlocklistState.Success(compiled.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blocklists", e)
            _state.value = BlocklistState.Error(e.message ?: "Unknown error")
        }
    }
}