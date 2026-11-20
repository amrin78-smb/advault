package com.advault.vpn.core

import com.advault.core.common.FilterMode
import com.advault.data.repository.AppRepository

/**
 * Per-app mode resolution:
 * - unknown uid (-1, attribution failed) -> the global default mode from settings;
 * - known uid -> AppRepository.modeForUid (null = excluded -> forwarded unfiltered);
 * - repository failure -> default mode (fail safe: keep filtering).
 */
class RepositoryAppPolicy(
    private val apps: AppRepository,
    private val defaultMode: () -> FilterMode,
) : AppPolicy {
    override fun modeForUid(uid: Int): FilterMode? {
        if (uid < 0) return defaultMode()
        return try { apps.modeForUid(uid) } catch (_: Exception) { defaultMode() }
    }

    override fun packageForUid(uid: Int): String? =
        if (uid < 0) null else try { apps.packageForUid(uid) } catch (_: Exception) { null }
}
