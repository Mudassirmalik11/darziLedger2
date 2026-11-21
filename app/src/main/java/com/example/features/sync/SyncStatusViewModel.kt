package com.example.features.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.model.SyncState
import com.example.data.local.entities.OrderNumberReservationEntity
import com.example.data.local.entities.SyncConflictEntity
import com.example.data.local.entities.SyncMetadataEntity
import com.example.data.local.repository.DeviceSyncRepository
import com.example.data.sync.ConflictResolutionChoice
import com.example.data.sync.ConnectivityMonitor
import com.example.data.sync.SyncEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SyncStatusViewModel(
  private val syncEngine: SyncEngine,
  private val deviceSyncRepository: DeviceSyncRepository,
  connectivityMonitor: ConnectivityMonitor,
) : ViewModel() {

  val isOnline: StateFlow<Boolean> = connectivityMonitor.isOnline

  val syncState: StateFlow<SyncState> = syncEngine.syncState

  val lastSyncError: StateFlow<String?> = syncEngine.lastError

  val pendingOutboxCount: StateFlow<Int> = deviceSyncRepository.observePendingOutboxCount()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

  val unresolvedConflicts: StateFlow<List<SyncConflictEntity>> =
    deviceSyncRepository.observeUnresolvedConflicts()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val syncMetadata: StateFlow<SyncMetadataEntity?> = deviceSyncRepository.observeDeviceConfig()
    .flatMapLatest { device ->
      if (device == null) flowOf(null) else deviceSyncRepository.observeSyncMetadata(device.deviceId)
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  val reservations: StateFlow<List<OrderNumberReservationEntity>> =
    deviceSyncRepository.observeDeviceConfig()
      .flatMapLatest { device ->
        if (device == null) flowOf(emptyList()) else deviceSyncRepository.observeReservations(device.deviceId)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val _actionMessage = MutableStateFlow<String?>(null)
  val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

  fun syncNow() {
    if (_isBusy.value) return
    viewModelScope.launch {
      _isBusy.value = true
      val result = syncEngine.syncNow()
      _isBusy.value = false
      result.fold(
        onSuccess = { summary ->
          _actionMessage.value = buildString {
            append("Sync complete: ")
            append("${summary.pushedCount} pushed, ${summary.pulledCount} pulled")
            if (summary.assignedOrderNumbers > 0) {
              append(", ${summary.assignedOrderNumbers} order # assigned")
            }
            if (summary.conflictsDetected > 0) {
              append(", ${summary.conflictsDetected} conflict(s) flagged")
            }
          }
        },
        onFailure = { error ->
          _actionMessage.value = error.localizedMessage ?: "Sync failed."
        },
      )
    }
  }

  fun reserveBlock() {
    if (_isBusy.value) return
    viewModelScope.launch {
      _isBusy.value = true
      val result = syncEngine.reserveAdditionalNumberBlock()
      _isBusy.value = false
      result.fold(
        onSuccess = { block ->
          _actionMessage.value = "Reserved order numbers #${block.startNumber}–#${block.endNumber}"
        },
        onFailure = { error ->
          _actionMessage.value = error.localizedMessage ?: "Could not reserve block."
        },
      )
    }
  }

  fun resolveConflict(conflictId: String, choice: ConflictResolutionChoice) {
    if (_isBusy.value) return
    viewModelScope.launch {
      _isBusy.value = true
      val result = syncEngine.resolveConflict(conflictId, choice)
      _isBusy.value = false
      result.fold(
        onSuccess = {
          _actionMessage.value = if (choice == ConflictResolutionChoice.KEEP_LOCAL) {
            "Kept local version and queued cloud update."
          } else {
            "Applied cloud version to local ledger."
          }
        },
        onFailure = { error ->
          _actionMessage.value = error.localizedMessage ?: "Failed to resolve conflict."
        },
      )
    }
  }

  fun clearMessage() {
    _actionMessage.value = null
  }

  class Factory(
    private val syncEngine: SyncEngine,
    private val deviceSyncRepository: DeviceSyncRepository,
    private val connectivityMonitor: ConnectivityMonitor,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return SyncStatusViewModel(
        syncEngine = syncEngine,
        deviceSyncRepository = deviceSyncRepository,
        connectivityMonitor = connectivityMonitor,
      ) as T
    }
  }
}
