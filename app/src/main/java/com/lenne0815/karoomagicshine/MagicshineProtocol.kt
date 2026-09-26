package com.lenne0815.karoomagicshine

enum class MagicshineModule {
    MODULE_1,
    MODULE_2,
}

enum class MagicshineMode {
    STEADY,
    SOS,
    BLITZ,
}

enum class Hori1300Mode { LOW, MED, HIGH, HIGH_BEAM }

object MagicshineProtocol {
    // Read-only queries verified in the full-discharge capture. Never send A2/A6 here.
    val telemetryRequests = listOf("DE06A400A2ED", "DE06A100A7ED")

    fun buildPresetFrame(module: MagicshineModule, level: Int): String {
        val normalized = level.coerceIn(0, 100)
        return when (module) {
            MagicshineModule.MODULE_1 -> when (normalized) {
                0 -> "DE14A20101010100000000000000000000BB0DED"
                25 -> "DE14A20101010114000150000000000000BB48ED"
                50 -> "DE14A2010101013C000150000000000000BB60ED"
                75 -> buildBrightnessFrame(module, 75)
                else -> "DE14A20101010163000150000000000000BB3FED"
            }
            MagicshineModule.MODULE_2 -> when (normalized) {
                0 -> "DE14A20101010100000000000000000000BB0DED"
                25 -> "DE14A2010200010A010114000000000000BB11ED"
                50 -> "DE14A2010200010A010132000000000000BB37ED"
                75 -> buildBrightnessFrame(module, 75)
                else -> "DE14A2010200010A010164000000000000BB61ED"
            }
        }
    }

    fun buildBrightnessFrame(module: MagicshineModule, percent: Int): String {
        val value = percent.coerceIn(0, 100)
        return when (module) {
            MagicshineModule.MODULE_1 -> {
                val checksum = value xor 0x5C
                "DE14A201010101%02X000150000000000000BB%02XED".format(value, checksum)
            }
            MagicshineModule.MODULE_2 -> buildModule2Frame(modeCode = 0x01, value = value)
        }
    }

    fun buildModeFrame(module: MagicshineModule, mode: MagicshineMode): String {
        return when (module) {
            MagicshineModule.MODULE_1 -> {
                when (mode) {
                    MagicshineMode.STEADY -> "DE14A2010101010A000150000000000000BB56ED"
                    MagicshineMode.SOS -> "DE14A20101010263000150000000000000BB3CED"
                    MagicshineMode.BLITZ -> "DE14A20101010363000150000000000000BB3DED"
                }
            }
            MagicshineModule.MODULE_2 -> {
                val modeCode = when (mode) {
                    MagicshineMode.STEADY -> 0x01
                    MagicshineMode.SOS -> 0x02
                    MagicshineMode.BLITZ -> 0x03
                }
                buildModule2Frame(modeCode = modeCode, value = 0x64)
            }
        }
    }

    // HORI 1300 (M1-BO/M1-B0) uses the M1 two-channel command layout.
    // The low-beam presets use the captured M1 brightness values; high beam
    // is selected with the separate model code.
    fun buildHori1300OffFrame(): String = buildHoriM1Frame(model = 0x01, brightness = 0x00)

    fun buildHori1300Frame(mode: Hori1300Mode): String {
        return when (mode) {
            Hori1300Mode.LOW -> buildHoriM1Frame(model = 0x01, brightness = 0x14)
            Hori1300Mode.MED -> buildHoriM1Frame(model = 0x01, brightness = 0x3C)
            Hori1300Mode.HIGH -> buildHoriM1Frame(model = 0x01, brightness = 0x63)
            Hori1300Mode.HIGH_BEAM -> buildHoriM1Frame(model = 0x02, brightness = 0x63)
        }
    }

    private fun buildHoriM1Frame(model: Int, brightness: Int): String {
        val content = IntArray(14)
        content[0] = 0x01
        content[4] = 0x01
        content[5] = model
        content[6] = brightness
        content[13] = 0xBB
        val frame = IntArray(20)
        frame[0] = 0xDE
        frame[1] = 0x14
        frame[2] = 0xA2
        frame[3] = 0x01
        for (i in content.indices) frame[4 + i] = content[i]
        var checksum = frame[1]
        for (i in 2 until frame.size - 2) checksum = checksum xor frame[i]
        frame[frame.size - 2] = checksum and 0xFF
        frame[frame.size - 1] = 0xED
        return frame.joinToString("") { "%02X".format(it) }
    }

    fun parseBatteryPercent(frameHex: String): Int? {
        val clean = frameHex.uppercase()
        if (!clean.startsWith("DE13B4") || clean.length < 16) return null
        val payload = clean.removePrefix("DE13B4").dropLast(4)
        val bytes = payload.chunked(2).mapNotNull { it.toIntOrNull(16) }
        return bytes.getOrNull(5)?.takeIf { it in 0..100 }
    }

    fun parseBatteryStatus(frameHex: String, lampName: String?): String? {
        val value = parseBatteryPercent(frameHex) ?: return null
        if (!usesCoarseBatteryTelemetry(lampName)) return "$value%"
        return when (value) {
            100 -> "HIGH"
            50 -> "MID"
            30 -> "LOW"
            else -> null
        }
    }

    fun usesCoarseBatteryTelemetry(lampName: String?): Boolean {
        val normalized = lampName?.replace("\u0000", "")?.trim().orEmpty()
        return normalized.startsWith("M2-B0", ignoreCase = true) ||
            normalized.startsWith("M2-BO", ignoreCase = true)
    }

    fun parseTemperatureCelsius(frameHex: String): Int? {
        val clean = frameHex.uppercase()
        if (!clean.startsWith("DE0DB1")) return null
        val markerIndex = clean.indexOf("1703")
        if (markerIndex == -1 || clean.length < markerIndex + 6) return null
        val temperatureHex = clean.substring(markerIndex + 4, markerIndex + 6)
        val temperature = temperatureHex.toIntOrNull(16) ?: return null
        return temperature.takeIf { it in 0..120 }
    }

    private fun buildModule2Frame(modeCode: Int, value: Int): String {
        val checksum = value xor (modeCode + 0x04)
        return "DE14A2010200010A01%02X%02X000000000000BB%02XED".format(modeCode, value, checksum)
    }
}
