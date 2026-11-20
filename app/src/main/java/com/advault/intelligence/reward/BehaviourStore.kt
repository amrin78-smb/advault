package com.advault.intelligence.reward

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.advault.data.models.GameAdBehaviour
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted per-game Reward Assistant behaviour. Absent = [GameAdBehaviour.DEFAULT]. */
interface BehaviourStore {
    val behaviours: Flow<Map<String, GameAdBehaviour>>
    suspend fun current(): Map<String, GameAdBehaviour> = behaviours.first()
    suspend fun set(packageName: String, behaviour: GameAdBehaviour)
}

private val Context.rewardDataStore: DataStore<Preferences> by preferencesDataStore(name = "reward_assistant")

@Singleton
class DataStoreBehaviourStore @Inject constructor(@ApplicationContext private val context: Context) : BehaviourStore {
    override val behaviours: Flow<Map<String, GameAdBehaviour>> = context.rewardDataStore.data.map { prefs ->
        prefs.asMap().mapNotNull { (k, v) ->
            if (!k.name.startsWith(PREFIX)) return@mapNotNull null
            val b = GameAdBehaviour.entries.firstOrNull { it.name == v } ?: return@mapNotNull null
            if (b == GameAdBehaviour.DEFAULT) null else k.name.removePrefix(PREFIX) to b
        }.toMap()
    }

    override suspend fun set(packageName: String, behaviour: GameAdBehaviour) {
        val key = stringPreferencesKey(PREFIX + packageName)
        context.rewardDataStore.edit {
            if (behaviour == GameAdBehaviour.DEFAULT) it.remove(key) else it[key] = behaviour.name
        }
    }

    private companion object { const val PREFIX = "behaviour:" }
}

/** In-memory store for tests. */
class InMemoryBehaviourStore(initial: Map<String, GameAdBehaviour> = emptyMap()) : BehaviourStore {
    private val state = MutableStateFlow(initial)
    override val behaviours: Flow<Map<String, GameAdBehaviour>> = state
    override suspend fun set(packageName: String, behaviour: GameAdBehaviour) {
        state.value = if (behaviour == GameAdBehaviour.DEFAULT) state.value - packageName else state.value + (packageName to behaviour)
    }
}
