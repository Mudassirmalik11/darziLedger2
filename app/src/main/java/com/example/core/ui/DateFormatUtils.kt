package com.example.core.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateFormatUtils {
  private val datePattern = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
  private val fullDatePattern = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
  private val shortDatePattern = SimpleDateFormat("dd MMM", Locale.ENGLISH)
  private val dateTimePattern = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH)
  private val fullDateTimePattern = SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.ENGLISH)

  fun formatDate(millis: Long): String = datePattern.format(Date(millis))

  fun formatFullDate(millis: Long): String = fullDatePattern.format(Date(millis))

  fun formatShortDate(millis: Long): String = shortDatePattern.format(Date(millis))

  fun formatDateTime(millis: Long): String = dateTimePattern.format(Date(millis))

  fun formatFullDateTime(millis: Long): String = fullDateTimePattern.format(Date(millis))

  fun formatInches(value: Double): String {
    val rounded = kotlin.math.round(value * 100.0) / 100.0
    return if (rounded % 1.0 == 0.0) {
      "${rounded.toInt()}\""
    } else {
      val text = rounded.toString().trimEnd('0').trimEnd('.')
      "$text\""
    }
  }
}
