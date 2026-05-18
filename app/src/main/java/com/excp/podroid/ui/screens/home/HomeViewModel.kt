package com.excp.podroid.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.data.repository.UpdateInfo
import com.excp.podroid.data.repository.UpdateRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import com.excp.podroid.service.PodroidService
import com.excp.podroid.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Aggregated Home metadata used by the data sections (resources, network,
 * last-session). `Resources` is shown in the meta row in every state; the
 * Network/LastSession sections render conditionally based on vmState.
 */
data class HomeMeta(
    val ramMb: Int,
    val cpus: Int,
    val storageGb: Int,
    val sshEnabled: Boolean,
    val portForwardCount: Int,
    val lastBootDurationMs: Long,
) {
    val resourcesLabel: String = "${formatRam(ramMb)} · $cpus CPU · ${storageGb} GB"

    private fun formatRam(mb: Int): String =
        if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val podroidQemu: PodroidQemu,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = podroidQemu.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    val bootStage: StateFlow<String> = podroidQemu.bootStage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val storageAccessEnabled: StateFlow<Boolean> = settingsRepository.storageAccessEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Aggregated metadata for the Home data sections. */
    val meta: StateFlow<HomeMeta> = combine(
        settingsRepository.vmRamMb,
        settingsRepository.vmCpus,
        settingsRepository.storageSizeGb,
        settingsRepository.sshEnabled,
        portForwardRepository.rules.map { it.size }.distinctUntilChanged(),
        settingsRepository.lastBootDurationMs,
    ) { values ->
        HomeMeta(
            ramMb = values[0] as Int,
            cpus = values[1] as Int,
            storageGb = values[2] as Int,
            sshEnabled = values[3] as Boolean,
            portForwardCount = values[4] as Int,
            lastBootDurationMs = values[5] as Long,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HomeMeta(ramMb = 512, cpus = 2, storageGb = 8, sshEnabled = false, portForwardCount = 0, lastBootDurationMs = 0L),
    )

    /**
     * Seconds since the VM transitioned to Running. Null when not running.
     * Recomputed once per second by the ticker flow; the underlying timestamp
     * is captured on the first observation of the Running state.
     */
    val uptimeSeconds: StateFlow<Long?> = flow {
        var runningSince: Long? = null
        var lastState: VmState? = null
        podroidQemu.state.collect { state ->
            if (state is VmState.Running && lastState !is VmState.Running) {
                runningSince = System.currentTimeMillis()
            } else if (state !is VmState.Running) {
                runningSince = null
            }
            lastState = state
            if (state is VmState.Running) {
                emit((System.currentTimeMillis() - (runningSince ?: System.currentTimeMillis())) / 1000)
            } else {
                emit(null)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Independent ticker — drives display refresh every second while running.
    // (uptimeSeconds above only re-emits on state change; we need a ticker to
    // re-evaluate display every second.)
    val uptimeTicker: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    /** Phone IPv4 — cheap, lazily recomputed when the screen reads it. */
    fun phoneIp(): String = NetworkUtils.localIpv4()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private var runningSinceMs: Long? = null

    init {
        checkForUpdate()
        // Maintain runningSinceMs for the synchronous read path used by uptime formatter.
        viewModelScope.launch {
            var lastWasRunning = false
            podroidQemu.state.collect { state ->
                val nowRunning = state is VmState.Running
                if (nowRunning && !lastWasRunning) runningSinceMs = System.currentTimeMillis()
                if (!nowRunning) runningSinceMs = null
                lastWasRunning = nowRunning
            }
        }
    }

    /** Format "Up Xm Ys" / "Up Xh Ym" from runningSinceMs. */
    fun uptimeLabel(@Suppress("UNUSED_PARAMETER") tickerTrigger: Long): String? {
        val since = runningSinceMs ?: return null
        val totalSec = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0   -> "Up ${hours}h ${minutes}m"
            minutes > 0 -> "Up ${minutes}m ${seconds}s"
            else        -> "Up ${seconds}s"
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            try {
                val info = updateRepository.checkForUpdate(BuildConfig.VERSION_NAME) ?: return@launch
                if (!updateRepository.isDismissed(info.latestVersion)) {
                    _updateInfo.value = info
                }
            } catch (_: Exception) { }
        }
    }

    fun dismissUpdate() {
        val version = _updateInfo.value?.latestVersion ?: return
        _updateInfo.value = null
        viewModelScope.launch { updateRepository.dismissUpdate(version) }
    }

    fun startPodroid() = PodroidService.start(context)

    fun setStorageAccessEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setStorageAccessEnabled(enabled) }
    }

    fun stopVm() = PodroidService.stop(context)

    fun restartVm() {
        PodroidService.stop(context)
        viewModelScope.launch {
            withTimeoutOrNull(10_000) {
                podroidQemu.state.first { state ->
                    state is VmState.Stopped || state is VmState.Idle || state is VmState.Error
                }
            }
            PodroidService.start(context)
        }
    }
}
