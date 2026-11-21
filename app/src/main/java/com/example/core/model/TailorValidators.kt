package com.example.core.model

object TailorValidators {

  fun validateCustomer(name: String, phone: String): String? {
    if (name.trim().length < 2) {
      return "Customer name must be at least 2 characters."
    }
    if (phone.isNotBlank() && !PhoneNumberUtils.isValidPakistaniPhone(phone)) {
      return "Enter a valid Pakistani phone number (e.g., 0300-1234567)."
    }
    return null
  }

  fun validateMeasurement(snapshot: MeasurementSnapshot): String? {
    val fields = listOf(
      "Kameez Lambai" to (snapshot.kameezLambai to (20.0..60.0)),
      "Tera" to (snapshot.tera to (10.0..30.0)),
      "Bazu" to (snapshot.bazu to (12.0..35.0)),
      "Chati" to (snapshot.chati to (15.0..65.0)),
      "Kamar" to (snapshot.kamar to (14.0..65.0)),
      "Ghera / Daman" to (snapshot.gheraDaman to (15.0..65.0)),
      "Gala / Collar" to (snapshot.galaCollar to (10.0..25.0)),
      "Cuff" to (snapshot.cuff to (0.0..15.0)),
      "Shalwar Lambai" to (snapshot.shalwarLambai to (20.0..55.0)),
      "Paincha" to (snapshot.paincha to (4.0..20.0)),
      "Ghera / Asan" to (snapshot.gheraAsan to (10.0..35.0)),
    )
    for ((label, valueAndRange) in fields) {
      val (value, range) = valueAndRange
      if (value.isNaN() || value.isInfinite() || value !in range) {
        return "$label must be between ${range.start} and ${range.endInclusive} inches."
      }
    }
    return null
  }

  fun validateNewOrder(
    suitCount: Int,
    totalAmountPkr: Long,
    initialAdvancePkr: Long,
  ): String? {
    if (suitCount < 1 || suitCount > 100) {
      return "Suit count must be between 1 and 100."
    }
    if (totalAmountPkr < 0L) {
      return "Total amount cannot be negative."
    }
    if (initialAdvancePkr < 0L) {
      return "Advance payment cannot be negative."
    }
    if (initialAdvancePkr > totalAmountPkr) {
      return "Advance payment cannot exceed total bill."
    }
    return null
  }

  fun validatePayment(
    amountPkr: Long,
    remainingBalancePkr: Long,
  ): String? {
    if (amountPkr <= 0L) {
      return "Payment amount must be greater than Rs. 0."
    }
    if (amountPkr > remainingBalancePkr) {
      return "Payment cannot exceed remaining balance of Rs. $remainingBalancePkr."
    }
    return null
  }
}
