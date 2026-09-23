package com.example.illegalcapture

/** Recording sizes. 1440p stays in the table for duration math; CameraX Recorder 1.4 cannot select it. */
object RecordingTier {
    data class Tier(val id: String, val label: String, val width: Int, val height: Int)

    const val CAP_BYTES = 50L * 1024 * 1024
    private const val BASE_BPS = 4_000_000L

    val all = listOf(
        Tier("720", "720p", 1280, 720),
        Tier("1080", "1080p", 1920, 1080),
        Tier("1440", "1440p", 2560, 1440),
        Tier("2160", "2160p", 3840, 2160),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all[1]

    /** CameraX Quality name, or null when this recorder cannot target the tier. */
    fun qualityName(id: String) = when (id) {
        "720" -> "HD"
        "1080" -> "FHD"
        "2160" -> "UHD"
        else -> null
    }

    fun recordable() = all.filter { qualityName(it.id) != null }

    fun offered(qualityNames: Set<String>) = recordable().filter { qualityName(it.id) in qualityNames }

    fun bitrate(tier: Tier) = (BASE_BPS * tier.width * tier.height / (1920 * 1080)).coerceAtLeast(500_000)

    fun maxSeconds(tier: Tier) = (CAP_BYTES * 8 / bitrate(tier)).toInt()

    /** Saved choice if that tier is offered; otherwise the highest tier at or below 1080p. */
    fun choose(offered: List<Tier>, saved: String): Tier {
        val pool = offered.ifEmpty { listOf(byId("1080")) }
        pool.firstOrNull { it.id == saved }?.let { return it }
        return pool.filter { it.height <= 1080 }.maxByOrNull { it.height } ?: pool.minBy { it.height }
    }
}
