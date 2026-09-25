package com.tgfinder.search

import com.tgfinder.data.db.TmdbMatchEntity
import java.util.concurrent.ConcurrentHashMap

/** In-memory hand-off of search results between the results grid and the detail screen. */
class SessionStore {
    private val groups = ConcurrentHashMap<String, MediaGroup>()
    private val matches = ConcurrentHashMap<String, TmdbMatchEntity>()

    fun put(group: MediaGroup, match: TmdbMatchEntity?) {
        groups[group.key] = group
        if (match != null) matches[group.key] = match else matches.remove(group.key)
    }

    fun group(key: String): MediaGroup? = groups[key]
    fun match(key: String): TmdbMatchEntity? = matches[key]
}
