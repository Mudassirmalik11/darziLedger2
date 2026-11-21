package com.example.features.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.model.ShopUserRole
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.ShopPreferences
import com.example.core.preferences.ShopPreferencesRepository
import com.example.core.preferences.ThemeMode
import com.example.data.backup.BackupSummary
import com.example.data.backup.ShopBackupRepository
import com.example.data.local.entities.DeviceConfigEntity
import com.example.data.local.entities.PrinterConfigEntity
import com.example.data.local.repository.DeviceSyncRepository
import com.example.data.local.repository.ShopRepository
import com.example.data.remote.AuthSessionProvider
import com.example.data.remote.AuthUser
import com.example.data.remote.DeviceRegistrationDoc
import com.example.data.remote.FirebaseAuthSessionProvider
import com.example.data.remote.OperationType
import com.example.data.remote.ShopCloudRepository
import com.example.data.remote.ShopStaffUserDoc
import com.example.data.remote.handleFirestoreError
import com.example.features.printing.DiscoveredPrinter
import com.example.features.printing.ThermalPrintManager
import com.example.features.printing.ThermalPrinterClient
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface DevicesUiState {
  data object Loading : DevicesUiState
  data class Success(val devices: List<DeviceRegistrationDoc>) : DevicesUiState
  data class Error(val message: String) : DevicesUiState
}

sealed interface StaffUsersUiState {
  data object Loading : StaffUsersUiState
  data class Success(val users: List<ShopStaffUserDoc>) : StaffUsersUiState
  data class Error(val message: String) : StaffUsersUiState
}

data class CreatedStaffCredentials(
  val displayName: String,
  val email: String,
  val password: String,
)

data class PendingBackupRestore(
  val rawJson: String,
  val summary: BackupSummary,
)

class SettingsViewModel(
  private val currentUserId: String,
  private val currentUser: AuthUser,
  private val repository: ShopPreferencesRepository,
  private val shopRepository: ShopRepository,
  private val deviceSyncRepository: DeviceSyncRepository,
  private val shopBackupRepository: ShopBackupRepository,
  private val shopCloudRepository: ShopCloudRepository,
  private val authSessionProvider: AuthSessionProvider,
  private val thermalPrinterClient: ThermalPrinterClient,
  private val thermalPrintManager: ThermalPrintManager,
  private val shopId: String = currentUserId,
  val userRole: ShopUserRole = ShopUserRole.OWNER,
) : ViewModel() {

  val isOwner: Boolean = userRole == ShopUserRole.OWNER
  val activeShopId: String = shopId

  val preferences: StateFlow<ShopPreferences> =
    repository.preferencesFlow.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = ShopPreferences(),
    )

  val defaultPrinter: StateFlow<PrinterConfigEntity?> =
    deviceSyncRepository.observeDefaultPrinter().stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = null,
    )

  val localDevice: StateFlow<DeviceConfigEntity?> =
    deviceSyncRepository.observeDeviceConfig().stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = null,
    )

  val registeredDevicesState: StateFlow<DevicesUiState> =
    shopCloudRepository.observeRegisteredDevices(
      shopId = shopId,
      userUid = currentUserId,
      userRole = userRole,
    )
      .map<List<DeviceRegistrationDoc>, DevicesUiState> { DevicesUiState.Success(it) }
      .catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
          .filterIsInstance<FirebaseFirestoreException>()
          .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        if (authSessionProvider.currentUser?.uid == currentUserId) {
          handleFirestoreError(rootError, OperationType.LIST, "shops/$shopId/devices")
        }
        emit(
          DevicesUiState.Error(
            rootError.localizedMessage ?: "Device list unavailable offline.",
          ),
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DevicesUiState.Loading,
      )

  val staffUsersState: StateFlow<StaffUsersUiState> = if (isOwner) {
    shopCloudRepository.observeShopStaffUsers(shopId)
      .map<List<ShopStaffUserDoc>, StaffUsersUiState> { StaffUsersUiState.Success(it) }
      .catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
          .filterIsInstance<FirebaseFirestoreException>()
          .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val rootError = firestoreError ?: error
        if (authSessionProvider.currentUser?.uid == currentUserId) {
          handleFirestoreError(rootError, OperationType.LIST, "shops/$shopId/staff_users")
        }
        emit(
          StaffUsersUiState.Error(
            rootError.localizedMessage ?: "Shop users unavailable offline.",
          ),
        )
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StaffUsersUiState.Loading,
      )
  } else {
    MutableStateFlow(StaffUsersUiState.Success(emptyList())).asStateFlow()
  }

  val signedInUser: AuthUser = currentUser

  private val _savedMessageCounter = MutableStateFlow(0)
  val savedMessageCounter: StateFlow<Int> = _savedMessageCounter.asStateFlow()

  private val _deviceActionMessage = MutableStateFlow<String?>(null)
  val deviceActionMessage: StateFlow<String?> = _deviceActionMessage.asStateFlow()

  private val _isCreatingStaffUser = MutableStateFlow(false)
  val isCreatingStaffUser: StateFlow<Boolean> = _isCreatingStaffUser.asStateFlow()

  private val _staffActionError = MutableStateFlow<String?>(null)
  val staffActionError: StateFlow<String?> = _staffActionError.asStateFlow()

  private val _createdStaffCredentials = MutableStateFlow<CreatedStaffCredentials?>(null)
  val createdStaffCredentials: StateFlow<CreatedStaffCredentials?> =
    _createdStaffCredentials.asStateFlow()

  private val _availablePrinters = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())
  val availablePrinters: StateFlow<List<DiscoveredPrinter>> = _availablePrinters.asStateFlow()

  private val _isScanningPrinters = MutableStateFlow(false)
  val isScanningPrinters: StateFlow<Boolean> = _isScanningPrinters.asStateFlow()

  private val _isPrintBusy = MutableStateFlow(false)
  val isPrintBusy: StateFlow<Boolean> = _isPrintBusy.asStateFlow()

  private val _printerError = MutableStateFlow<String?>(null)
  val printerError: StateFlow<String?> = _printerError.asStateFlow()

  private val _isBackupBusy = MutableStateFlow(false)
  val isBackupBusy: StateFlow<Boolean> = _isBackupBusy.asStateFlow()

  private val _backupError = MutableStateFlow<String?>(null)
  val backupError: StateFlow<String?> = _backupError.asStateFlow()

  private val _pendingRestore = MutableStateFlow<PendingBackupRestore?>(null)
  val pendingRestore: StateFlow<PendingBackupRestore?> = _pendingRestore.asStateFlow()

  private var scanJob: Job? = null

  fun clearDeviceMessage() {
    _deviceActionMessage.value = null
  }

  fun clearStaffActionError() {
    _staffActionError.value = null
  }

  fun dismissCreatedCredentials() {
    _createdStaffCredentials.value = null
  }

  fun clearPrinterError() {
    _printerError.value = null
  }

  fun clearBackupError() {
    _backupError.value = null
  }

  fun dismissPendingRestore() {
    _pendingRestore.value = null
  }

  fun createStaffUser(
    displayName: String,
    email: String,
    password: String,
    confirmPassword: String,
  ) {
    if (!isOwner || _isCreatingStaffUser.value) return
    val cleanName = displayName.trim()
    val cleanEmail = email.trim()
    _staffActionError.value = null

    if (cleanName.isEmpty()) {
      _staffActionError.value = "Enter the user's name."
      return
    }
    if (!FirebaseAuthSessionProvider.isValidEmail(cleanEmail)) {
      _staffActionError.value = "Enter a valid unique email address."
      return
    }
    if (password.length < 6) {
      _staffActionError.value = "Password must be at least 6 characters."
      return
    }
    if (password != confirmPassword) {
      _staffActionError.value = "Passwords do not match."
      return
    }

    viewModelScope.launch {
      _isCreatingStaffUser.value = true
      shopCloudRepository.createStaffUser(
        displayName = cleanName,
        email = cleanEmail,
        password = password,
      ).fold(
        onSuccess = { created ->
          _isCreatingStaffUser.value = false
          _createdStaffCredentials.value = CreatedStaffCredentials(
            displayName = created.displayName,
            email = created.email,
            password = password,
          )
          _deviceActionMessage.value = "User ${created.displayName} created."
        },
        onFailure = { err ->
          _isCreatingStaffUser.value = false
          _staffActionError.value = err.message ?: "Failed to create shop user."
        },
      )
    }
  }

  fun revokeStaffUser(staffUid: String) {
    if (!isOwner) return
    viewModelScope.launch {
      shopCloudRepository.revokeStaffUser(staffUid).fold(
        onSuccess = { _deviceActionMessage.value = "User access disabled." },
        onFailure = { err -> _deviceActionMessage.value = err.message ?: "Failed to disable user." },
      )
    }
  }

  fun reactivateStaffUser(staffUid: String) {
    if (!isOwner) return
    viewModelScope.launch {
      shopCloudRepository.reactivateStaffUser(staffUid).fold(
        onSuccess = { _deviceActionMessage.value = "User access re-enabled." },
        onFailure = { err -> _deviceActionMessage.value = err.message ?: "Failed to re-enable user." },
      )
    }
  }

  fun exportBackupToUri(context: Context, uri: Uri) {
    if (_isBackupBusy.value) return
    viewModelScope.launch {
      _isBackupBusy.value = true
      _backupError.value = null
      val jsonResult = shopBackupRepository.exportToJson()
      val writeResult = jsonResult.mapCatching { payload ->
        withContext(Dispatchers.IO) {
          context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
            stream.flush()
          } ?: error("Could not open backup destination file.")
        }
      }
      _isBackupBusy.value = false
      writeResult.fold(
        onSuccess = { _deviceActionMessage.value = "Shop JSON backup exported successfully." },
        onFailure = { err -> _backupError.value = err.message ?: "Backup export failed." },
      )
    }
  }

  fun inspectBackupFromUri(context: Context, uri: Uri) {
    if (_isBackupBusy.value) return
    viewModelScope.launch {
      _isBackupBusy.value = true
      _backupError.value = null
      val readResult = runCatching {
        withContext(Dispatchers.IO) {
          context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
          } ?: error("Could not read selected backup file.")
        }
      }
      _isBackupBusy.value = false
      readResult.fold(
        onSuccess = { raw -> previewRestoreJson(raw) },
        onFailure = { err -> _backupError.value = err.message ?: "Could not open backup file." },
      )
    }
  }

  fun previewRestoreJson(rawJson: String) {
    _backupError.value = null
    shopBackupRepository.inspectBackupSummary(rawJson).fold(
      onSuccess = { summary ->
        _pendingRestore.value = PendingBackupRestore(rawJson = rawJson, summary = summary)
      },
      onFailure = { err ->
        _backupError.value = err.message ?: "Backup validation failed."
      },
    )
  }

  fun confirmPendingRestore() {
    val pending = _pendingRestore.value ?: return
    if (_isBackupBusy.value) return
    viewModelScope.launch {
      _isBackupBusy.value = true
      _backupError.value = null
      val result = shopBackupRepository.restoreFromJson(pending.rawJson)
      _isBackupBusy.value = false
      result.fold(
        onSuccess = { report ->
          _pendingRestore.value = null
          _deviceActionMessage.value =
            "Restored ${report.customersRestored} customers, ${report.ordersRestored} orders, ${report.paymentsRestored} payments."
        },
        onFailure = { err ->
          _pendingRestore.value = null
          _backupError.value = err.message ?: "Restore failed; existing shop data was preserved."
        },
      )
    }
  }

  fun hasBluetoothConnectPermission(): Boolean = thermalPrinterClient.hasConnectPermission()

  fun loadPairedPrinters() {
    viewModelScope.launch {
      _printerError.value = null
      thermalPrinterClient.getPairedPrinters().fold(
        onSuccess = { list -> _availablePrinters.value = list },
        onFailure = { err -> _printerError.value = err.message ?: "Could not load paired printers." },
      )
    }
  }

  fun startNearbyScan() {
    scanJob?.cancel()
    _printerError.value = null
    if (!thermalPrinterClient.isBluetoothEnabled()) {
      _printerError.value = "Bluetooth is turned off. Please enable Bluetooth."
      return
    }
    scanJob = viewModelScope.launch {
      _isScanningPrinters.value = true
      try {
        val collectorJob = launch {
          thermalPrinterClient.discoverNearbyPrinters().collect { printer ->
            val current = _availablePrinters.value
            if (current.none { it.address == printer.address }) {
              _availablePrinters.value = current + printer
            }
          }
        }
        delay(10_000L)
        collectorJob.cancel()
      } finally {
        _isScanningPrinters.value = false
      }
    }
  }

  fun stopNearbyScan() {
    scanJob?.cancel()
    scanJob = null
    _isScanningPrinters.value = false
  }

  fun selectPrinter(printer: DiscoveredPrinter) {
    viewModelScope.launch {
      val widthMm = preferences.value.paperWidthMm
      deviceSyncRepository.savePrinterConfig(
        printerAddress = printer.address,
        printerName = printer.name,
        paperWidthMm = widthMm,
      )
      _deviceActionMessage.value = "Saved printer: ${printer.name}"
    }
  }

  fun printTestSlip() {
    if (_isPrintBusy.value) return
    viewModelScope.launch {
      _isPrintBusy.value = true
      _printerError.value = null
      val result = thermalPrintManager.printTestPage()
      _isPrintBusy.value = false
      result.fold(
        onSuccess = { msg -> _deviceActionMessage.value = msg },
        onFailure = { err -> _printerError.value = err.message ?: "Test print failed." },
      )
    }
  }

  fun saveShopProfile(shopName: String, shopContact: String) {
    if (!isOwner) {
      _deviceActionMessage.value = "Only the shop owner can edit shop profile details."
      return
    }
    viewModelScope.launch {
      repository.updateShopProfile(shopName = shopName, shopContact = shopContact)
      val savedShop = shopRepository.updateShopProfile(
        shopName = shopName,
        contactNumber = shopContact,
        email = currentUser.email,
      )
      shopCloudRepository.updateShopProfileRemote(
        shopName = savedShop.shopName,
        contactNumber = savedShop.contactNumber,
        version = savedShop.version,
      )
      _savedMessageCounter.value += 1
    }
  }

  fun setPaperWidth(paperWidthMm: Int) {
    viewModelScope.launch {
      repository.updatePaperWidth(paperWidthMm)
      val existing = deviceSyncRepository.observeDefaultPrinter().first()
      if (existing != null) {
        deviceSyncRepository.savePrinterConfig(
          printerAddress = existing.printerAddress,
          printerName = existing.printerName,
          paperWidthMm = paperWidthMm,
          connectionSettingsJson = existing.connectionSettingsJson,
        )
      }
    }
  }

  fun setThemeMode(themeMode: ThemeMode) {
    viewModelScope.launch {
      repository.updateThemeMode(themeMode)
    }
  }

  fun setLanguage(language: AppLanguage) {
    viewModelScope.launch {
      repository.updateLanguage(language)
    }
  }

  fun revokeDevice(deviceId: String) {
    viewModelScope.launch {
      shopCloudRepository.revokeDevice(deviceId, shopIdOverride = shopId).fold(
        onSuccess = { _deviceActionMessage.value = "Device access revoked." },
        onFailure = { err -> _deviceActionMessage.value = err.message ?: "Failed to revoke device." },
      )
    }
  }

  fun reauthorizeDevice(deviceId: String) {
    viewModelScope.launch {
      shopCloudRepository.reauthorizeDevice(deviceId, shopIdOverride = shopId).fold(
        onSuccess = { _deviceActionMessage.value = "Device re-authorized." },
        onFailure = { err -> _deviceActionMessage.value = err.message ?: "Failed to re-authorize device." },
      )
    }
  }

  fun signOut(context: Context) {
    viewModelScope.launch {
      authSessionProvider.signOut(context)
    }
  }

  class Factory(
    private val currentUserId: String,
    private val currentUser: AuthUser,
    private val repository: ShopPreferencesRepository,
    private val shopRepository: ShopRepository,
    private val deviceSyncRepository: DeviceSyncRepository,
    private val shopBackupRepository: ShopBackupRepository,
    private val shopCloudRepository: ShopCloudRepository,
    private val authSessionProvider: AuthSessionProvider,
    private val thermalPrinterClient: ThermalPrinterClient,
    private val thermalPrintManager: ThermalPrintManager,
    private val shopId: String = currentUserId,
    private val userRole: ShopUserRole = ShopUserRole.OWNER,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return SettingsViewModel(
        currentUserId = currentUserId,
        currentUser = currentUser,
        repository = repository,
        shopRepository = shopRepository,
        deviceSyncRepository = deviceSyncRepository,
        shopBackupRepository = shopBackupRepository,
        shopCloudRepository = shopCloudRepository,
        authSessionProvider = authSessionProvider,
        thermalPrinterClient = thermalPrinterClient,
        thermalPrintManager = thermalPrintManager,
        shopId = shopId,
        userRole = userRole,
      ) as T
    }
  }
}
