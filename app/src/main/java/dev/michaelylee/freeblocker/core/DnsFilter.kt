package dev.michaelylee.freeblocker.core

import android.util.Log

/**
 * This is the blocklist impl, which stores the rules in a set <string>
 */
class DnsFilter {
    private val TAG = "DnsFilter"

    private var blocklist: Set<String> = emptySet()

    fun updateBlocklist(newBlocklist: Set<String>) {
        this.blocklist = newBlocklist
        Log.d(TAG, "DnsFilter initialized with ${newBlocklist.size} static rules.")
    }

    fun shouldBlock(domain: String): Boolean {
        if (domain.isEmpty() || blocklist.isEmpty()) return false

        var candidate = domain.lowercase().trim()

        while (candidate.contains(".")) {
            if (blocklist.contains(candidate)) {
                Log.i(TAG, "Blocked query for: $domain (matched rule: $candidate)")
                return true
            }
            val next = candidate.substringAfter(".")
            // Stop before we reach a bare TLD (e.g. "com") — no blocklist rule
            // will ever be just a TLD, and checking it is unnecessary work.
            if (!next.contains(".")) break
            candidate = next
        }

        return false
    }

    fun clear() {
        this.blocklist = emptySet()
        Log.d(TAG, "DnsFilter memory cleared.")
    }
}