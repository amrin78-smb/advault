package com.advault.intelligence.reward

import android.content.Context
import android.util.Log
import com.advault.core.common.AdFormat
import com.advault.core.common.IoDispatcher
import com.advault.core.database.ActivityLogDao
import com.advault.core.database.ProviderObservationDao
import com.advault.data.models.GameAdBehaviour
import com.advault.data.models.GameRewardProfile
import com.advault.data.models.ObservedProviderActivity
import com.advault.data.repository.ProviderRepository
import com.advault.data.repository.RewardAssistantRepository
import com.advault.intelligence.apps.DefaultAppRepository
import com.advault.intelligence.provider.isAdvertising
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@Singleton
class DefaultRewardAssistantRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val apps: DefaultAppRepository,
    private val providers: ProviderRepository,
    private val observationDao: ProviderObservationDao,
    private val activityLogDao: ActivityLogDao,
    private val behaviourStore: BehaviourStore,
) : RewardAssistantRepository {

    @Volatile private var compatibility: GameCompatibilityDatabase? = null
    private val compatMutex = Mutex()

    override fun observeGames(): Flow<List<GameRewardProfile>> = combine(
        apps.records.filterNotNull(),
        providers.providers,
        observationDao.observeAll().debounce(1_000),
        behaviourStore.behaviours,
        flow { emit(loadCompatibility()) },
    ) { records, providerList, observations, behaviours, compat ->
        Inputs(records, providerList, observations, behaviours, compat)
    }.mapLatest { build(it) }.flowOn(io)

    override suspend fun setBehaviour(packageName: String, behaviour: GameAdBehaviour) {
        apps.applyBehaviour(packageName, behaviour)
    }

    private data class Inputs(
        val records: List<com.advault.intelligence.apps.AppRecord>,
        val providers: List<com.advault.data.models.AdProvider>,
        val observations: List<com.advault.core.database.ProviderObservation>,
        val behaviours: Map<String, GameAdBehaviour>,
        val compat: GameCompatibilityDatabase,
    )

    private suspend fun build(inp: Inputs): List<GameRewardProfile> {
        val byId = inp.providers.associateBy { it.id }
        val obsByPkg = inp.observations.groupBy { it.packageName }
        val candidates = inp.records.filter { r ->
            r.game.isGame ||
                r.sdkProviderIds.any { byId[it]?.isAdvertising() == true } ||
                obsByPkg[r.identity.packageName].orEmpty().any { byId[it.providerId]?.isAdvertising() == true }
        }
        return candidates.map { r ->
            val pkg = r.identity.packageName
            val observed = observedFor(pkg, obsByPkg[pkg].orEmpty(), byId)
            val behaviour = inp.behaviours[pkg] ?: GameAdBehaviour.DEFAULT
            val input = RewardInput(
                label = r.label,
                game = r.game,
                detectedSdks = r.sdkProviderIds.mapNotNull { byId[it] },
                observed = observed,
                providersById = byId,
                compatibility = inp.compat[pkg],
                behaviour = behaviour,
            )
            val support = RewardAssistant.classify(input)
            GameRewardProfile(
                packageName = pkg,
                label = r.label,
                icon = r.icon,
                detectedSdks = input.detectedSdks,
                observed = observed,
                compatibility = input.compatibility,
                support = support,
                explanation = RewardAssistant.explain(input, support),
                behaviour = behaviour,
            )
        }.let { profiles ->
            val games = candidates.filter { it.game.isGame }.map { it.identity.packageName }.toSet()
            profiles.sortedWith(compareByDescending<GameRewardProfile> { it.packageName in games }.thenBy { it.label.lowercase() })
        }
    }

    /**
     * Aggregated counts come from [ProviderObservationDao]; formats come from the domains of recent log
     * entries. Providers whose recent matches were only shared-infrastructure (non-blockable) domains are
     * dropped, since e.g. graph.facebook.com traffic is usually Facebook Login, not advertising.
     */
    private suspend fun observedFor(
        pkg: String,
        obs: List<com.advault.core.database.ProviderObservation>,
        byId: Map<String, com.advault.data.models.AdProvider>,
    ): List<ObservedProviderActivity> {
        if (obs.isEmpty()) return emptyList()
        val recent = runCatching { activityLogDao.providerActivityFor(pkg, RECENT_LIMIT) }.getOrDefault(emptyList())
        val formats = HashMap<String, MutableSet<AdFormat>>()
        val meaningful = HashSet<String>()
        val seen = HashSet<String>()
        for (e in recent) {
            val pid = e.providerId ?: continue
            seen += pid
            val m = providers.match(e.domain) ?: continue
            if (m.provider.id != pid || !m.domain.blockable) continue
            meaningful += pid
            formats.getOrPut(pid) { HashSet() } += m.domain.formats
        }
        return obs.mapNotNull { o ->
            val p = byId[o.providerId] ?: return@mapNotNull null
            if (o.providerId in seen && o.providerId !in meaningful) return@mapNotNull null
            ObservedProviderActivity(
                providerId = o.providerId,
                providerName = p.name,
                hits = o.hits,
                blocked = o.blocked,
                lastSeen = o.lastSeen,
                associatedFormats = formats[o.providerId].orEmpty() - AdFormat.UNKNOWN,
            )
        }
    }

    private suspend fun loadCompatibility(): GameCompatibilityDatabase {
        compatibility?.let { return it }
        return compatMutex.withLock {
            compatibility ?: run {
                val bundled = runCatching {
                    GameCompatibilityDatabase.parse(context.assets.open("intelligence/games.json").bufferedReader().use { it.readText() })
                }.onFailure { Log.e(TAG, "Bundled games.json invalid", it) }.getOrDefault(GameCompatibilityDatabase.EMPTY)
                val userFile = File(File(context.filesDir, "intelligence"), "games.json")
                val user = if (userFile.isFile && userFile.length() <= GameCompatibilityDatabase.MAX_BYTES) {
                    runCatching { GameCompatibilityDatabase.parse(userFile.readText()) }
                        .onFailure { Log.w(TAG, "Ignoring invalid ${userFile.name}: ${it.message}") }.getOrNull()
                } else null
                bundled.mergedWith(user).also { compatibility = it }
            }
        }
    }

    private companion object {
        const val TAG = "RewardAssistant"
        const val RECENT_LIMIT = 300
    }
}
