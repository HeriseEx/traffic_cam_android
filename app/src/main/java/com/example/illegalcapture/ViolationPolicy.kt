package com.example.illegalcapture

/** Extensible offence menu. Auto covers every driving kind; parking is separate. */
object ViolationPolicy {
    const val LEAD_MS = 8_000L
    const val MIN_HOLD_MS = 3_000L

    data class Mode(val id: String, val label: String, val kinds: Set<String>, val parking: Boolean = false)

    val drivingKinds = setOf(
        "RED_LIGHT", "EMERGENCY_LANE", "NO_SIGNAL", "OVERTAKE",
        "WRONG_WAY", "SOLID_LINE", "DANGEROUS_CHANGE", "CUT_IN",
    )

    val modes = listOf(
        Mode("AUTO", "Auto", drivingKinds),
        Mode("RED_LIGHT", "闯红灯", setOf("RED_LIGHT")),
        Mode("EMERGENCY_LANE", "侵走高速应急车道", setOf("EMERGENCY_LANE")),
        Mode("NO_SIGNAL", "变道不打灯", setOf("NO_SIGNAL")),
        Mode("OVERTAKE", "越线超车", setOf("OVERTAKE")),
        Mode("WRONG_WAY", "逆行", setOf("WRONG_WAY")),
        Mode("SOLID_LINE", "压实线", setOf("SOLID_LINE")),
        Mode("DANGEROUS_CHANGE", "危险变道", setOf("DANGEROUS_CHANGE")),
        Mode("CUT_IN", "加塞", setOf("CUT_IN")),
        Mode("ILLEGAL_PARKING", "主城区机动车乱停乱放", emptySet(), parking = true),
    )

    fun mode(id: String) = modes.firstOrNull { it.id == id } ?: modes.first()

    fun allows(modeId: String, kind: String) = kind in mode(modeId).kinds

    fun autoReady(modeId: String, kind: String, plate: String?, confirmed: Boolean) =
        allows(modeId, kind) && confirmed && !plate.isNullOrBlank()

    fun holdKept(durationMs: Long) = durationMs >= MIN_HOLD_MS

    /** Plates are area-weighted. Extra plates in frame stay; the parked car is the shared plate that is largest on the close-up. */
    fun sharedPlate(spot: Map<String, Float>, front: Map<String, Float>): String? {
        val shared = spot.keys.intersect(front.keys)
        return shared.maxByOrNull { front[it] ?: 0f }
    }

    fun parkingBlock(spot: Map<String, Float>, front: Map<String, Float>): String? = when {
        spot.isEmpty() || front.isEmpty() -> "两张都要识别到车牌"
        sharedPlate(spot, front) == null -> "两张车牌不一致，请重拍"
        else -> null
    }
}
