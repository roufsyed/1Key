package com.roufsyed.onekey.core.data.snapshot

/** Test double: answers [matchingIds] from [hits] and records every query it was asked. */
class FakeNotesSearch : NotesSearch {
    val hits = mutableMapOf<String, Set<String>>()
    val queries = mutableListOf<String>()

    override suspend fun matchingIds(query: String): Set<String> {
        queries += query
        return hits[query] ?: emptySet()
    }
}
