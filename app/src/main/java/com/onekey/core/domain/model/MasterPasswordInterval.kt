package com.onekey.core.domain.model

enum class MasterPasswordInterval(val hours: Int, val label: String) {
    HOURS_48(48, "48 hours"),
    HOURS_72(72, "3 days"),
    WEEK_1(168, "1 week"),
    WEEKS_3(504, "3 weeks");

    val millis: Long get() = hours * 3_600_000L
}
