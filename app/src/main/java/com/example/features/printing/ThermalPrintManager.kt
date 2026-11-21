package com.example.features.printing

import com.example.core.model.SnapshotJson
import com.example.core.preferences.ShopPreferences
import com.example.core.preferences.ShopPreferencesRepository
import com.example.data.local.daos.OrderWithCustomer
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.MeasurementEntity
import com.example.data.local.entities.PaymentEntity
import com.example.data.local.entities.PrinterConfigEntity
import com.example.data.local.repository.DeviceSyncRepository
import com.example.data.local.repository.ShopRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class ThermalPrintManager(
  private val printerClient: ThermalPrinterClient,
  private val deviceSyncRepository: DeviceSyncRepository,
  private val shopRepository: ShopRepository,
  private val shopPreferencesRepository: ShopPreferencesRepository,
) {

  fun observeDefaultPrinter(): Flow<PrinterConfigEntity?> =
    deviceSyncRepository.observeDefaultPrinter()

  private suspend fun safePreferences(): ShopPreferences =
    runCatching { shopPreferencesRepository.preferencesFlow.first() }
      .getOrElse { e ->
        if (e is CancellationException) throw e
        ShopPreferences()
      }

  suspend fun buildPreviewText(
    orderDetail: OrderWithCustomer,
    payments: List<PaymentEntity>,
    slipType: PrintSlipType,
  ): String {
    val prefs = safePreferences()
    val shop = shopRepository.ensureDefaultShop()
    val shopName = shop.shopName.takeIf { it.isNotBlank() } ?: prefs.shopName
    val shopContact = shop.contactNumber.takeIf { it.isNotBlank() } ?: prefs.shopContact
    val paperWidth = PaperWidth.fromMm(prefs.paperWidthMm)

    return when (slipType) {
      PrintSlipType.CUSTOMER_RECEIPT ->
        ThermalReceiptFormatter.formatCustomerReceiptLines(
          shopName = shopName,
          shopContact = shopContact,
          orderDetail = orderDetail,
          payments = payments,
          paperWidth = paperWidth,
          preserveUnicode = true,
          language = prefs.language,
        ).joinToString("\n")

      PrintSlipType.KARIGAR_SLIP -> {
        val measurement = runCatching {
          SnapshotJson.decodeMeasurement(orderDetail.order.measurementSnapshotJson)
        }.getOrElse {
          return "Order measurement snapshot is unavailable."
        }
        val style = runCatching {
          SnapshotJson.decodeStyle(orderDetail.order.styleSnapshotJson)
        }.getOrElse {
          return "Order style snapshot is unavailable."
        }
        ThermalReceiptFormatter.formatKarigarSlipLines(
          shopName = shopName,
          orderDetail = orderDetail,
          measurement = measurement,
          style = style,
          paperWidth = paperWidth,
          preserveUnicode = true,
          language = prefs.language,
        ).joinToString("\n")
      }

      PrintSlipType.MEASUREMENT_SLIP -> {
        val measurement = runCatching {
          SnapshotJson.decodeMeasurement(orderDetail.order.measurementSnapshotJson)
        }.getOrElse {
          return "Order measurement snapshot is unavailable."
        }
        val style = runCatching {
          SnapshotJson.decodeStyle(orderDetail.order.styleSnapshotJson)
        }.getOrElse {
          return "Order style snapshot is unavailable."
        }
        ThermalReceiptFormatter.formatMeasurementSlipLines(
          shopName = shopName,
          customerName = orderDetail.customerName,
          customerPhone = orderDetail.customerPhone,
          customerDisplayId = null,
          measurementLabel = "Standard",
          measurement = measurement,
          style = style,
          recordedAtMillis = orderDetail.order.createdAt,
          paperWidth = paperWidth,
          preserveUnicode = true,
          language = prefs.language,
        ).joinToString("\n")
      }

      PrintSlipType.TEST_PAGE -> {
        val printerName = deviceSyncRepository.observeDefaultPrinter().first()?.printerName ?: "Thermal Printer"
        ThermalReceiptFormatter.formatTestPageLines(
          shopName = shopName,
          printerName = printerName,
          paperWidth = paperWidth,
          preserveUnicode = true,
          language = prefs.language,
        ).joinToString("\n")
      }
    }
  }

  suspend fun buildMeasurementPreviewText(
    customer: CustomerEntity,
    customerDisplayId: String?,
    measurement: MeasurementEntity,
  ): String {
    val prefs = safePreferences()
    val shop = shopRepository.ensureDefaultShop()
    val shopName = shop.shopName.takeIf { it.isNotBlank() } ?: prefs.shopName
    val paperWidth = PaperWidth.fromMm(prefs.paperWidthMm)

    return ThermalReceiptFormatter.formatMeasurementSlipLines(
      shopName = shopName,
      customerName = customer.name,
      customerPhone = customer.phone,
      customerDisplayId = customerDisplayId,
      measurementLabel = measurement.label,
      measurement = measurement.toMeasurementSnapshot(),
      style = measurement.toStyleSnapshot(),
      recordedAtMillis = measurement.createdAt,
      paperWidth = paperWidth,
      preserveUnicode = true,
      language = prefs.language,
    ).joinToString("\n")
  }

  suspend fun printCustomerReceipt(
    orderDetail: OrderWithCustomer,
    payments: List<PaymentEntity>,
  ): Result<String> {
    val printer = deviceSyncRepository.observeDefaultPrinter().first()
      ?: return Result.failure(IllegalStateException("Select a Bluetooth thermal printer in Settings first."))
    val prefs = safePreferences()
    val shop = shopRepository.ensureDefaultShop()
    val shopName = shop.shopName.takeIf { it.isNotBlank() } ?: prefs.shopName
    val shopContact = shop.contactNumber.takeIf { it.isNotBlank() } ?: prefs.shopContact
    val width = PaperWidth.fromMm(prefs.paperWidthMm)

    val bytes = ThermalReceiptFormatter.formatCustomerReceiptBytes(
      shopName = shopName,
      shopContact = shopContact,
      orderDetail = orderDetail,
      payments = payments,
      paperWidth = width,
      language = prefs.language,
    )
    return printerClient.printBytes(printer.printerAddress, bytes).map {
      "Customer receipt sent to ${printer.printerName}"
    }
  }

  suspend fun printKarigarSlip(orderDetail: OrderWithCustomer): Result<String> {
    val printer = deviceSyncRepository.observeDefaultPrinter().first()
      ?: return Result.failure(IllegalStateException("Select a Bluetooth thermal printer in Settings first."))
    val prefs = safePreferences()
    val shop = shopRepository.ensureDefaultShop()
    val shopName = shop.shopName.takeIf { it.isNotBlank() } ?: prefs.shopName
    val width = PaperWidth.fromMm(prefs.paperWidthMm)

    val measurement = runCatching {
      SnapshotJson.decodeMeasurement(orderDetail.order.measurementSnapshotJson)
    }.getOrElse {
      return Result.failure(IllegalStateException("Order measurement snapshot is corrupted or missing."))
    }
    val style = runCatching {
      SnapshotJson.decodeStyle(orderDetail.order.styleSnapshotJson)
    }.getOrElse {
      return Result.failure(IllegalStateException("Order style snapshot is corrupted or missing."))
    }

    val bytes = ThermalReceiptFormatter.formatKarigarSlipBytes(
      shopName = shopName,
      orderDetail = orderDetail,
      measurement = measurement,
      style = style,
      paperWidth = width,
      language = prefs.language,
    )
    return printerClient.printBytes(printer.printerAddress, bytes).map {
      "Karigar workshop slip sent to ${printer.printerName}"
    }
  }

  suspend fun printMeasurementSlip(
    customer: CustomerEntity,
    customerDisplayId: String?,
    measurement: MeasurementEntity,
  ): Result<String> {
    val printer = deviceSyncRepository.observeDefaultPrinter().first()
      ?: return Result.failure(IllegalStateException("Select a Bluetooth thermal printer in Settings first."))
    val prefs = safePreferences()
    val shop = shopRepository.ensureDefaultShop()
    val shopName = shop.shopName.takeIf { it.isNotBlank() } ?: prefs.shopName
    val width = PaperWidth.fromMm(prefs.paperWidthMm)

    val bytes = ThermalReceiptFormatter.formatMeasurementSlipBytes(
      shopName = shopName,
      customerName = customer.name,
      customerPhone = customer.phone,
      customerDisplayId = customerDisplayId,
      measurementLabel = measurement.label,
      measurement = measurement.toMeasurementSnapshot(),
      style = measurement.toStyleSnapshot(),
      recordedAtMillis = measurement.createdAt,
      paperWidth = width,
      language = prefs.language,
    )
    return printerClient.printBytes(printer.printerAddress, bytes).map {
      "Measurement slip sent to ${printer.printerName}"
    }
  }

  suspend fun printTestPage(): Result<String> {
    val printer = deviceSyncRepository.observeDefaultPrinter().first()
      ?: return Result.failure(IllegalStateException("Select a Bluetooth thermal printer first."))
    val prefs = safePreferences()
    val shop = shopRepository.ensureDefaultShop()
    val shopName = shop.shopName.takeIf { it.isNotBlank() } ?: prefs.shopName
    val width = PaperWidth.fromMm(prefs.paperWidthMm)

    val bytes = ThermalReceiptFormatter.formatTestPageBytes(
      shopName = shopName,
      printerName = printer.printerName,
      paperWidth = width,
      language = prefs.language,
    )
    return printerClient.printBytes(printer.printerAddress, bytes).map {
      "Test slip sent to ${printer.printerName}"
    }
  }
}
