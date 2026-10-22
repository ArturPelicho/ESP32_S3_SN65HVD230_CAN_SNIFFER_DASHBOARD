package app.navexpress

/**
 * Plain-language names for the generic (SAE, P0xxx) fault codes a small
 * injected single-cylinder ECU can raise. Manufacturer codes (P1xxx and
 * others) are shown without a name.
 */
object DtcNames {
    private val names = mapOf(
        "P0030" to R.string.dtc_name_p0030,
        "P0031" to R.string.dtc_name_p0031,
        "P0032" to R.string.dtc_name_p0032,
        "P0105" to R.string.dtc_name_p0105,
        "P0106" to R.string.dtc_name_p0106,
        "P0107" to R.string.dtc_name_p0107,
        "P0108" to R.string.dtc_name_p0108,
        "P0110" to R.string.dtc_name_p0110,
        "P0112" to R.string.dtc_name_p0112,
        "P0113" to R.string.dtc_name_p0113,
        "P0115" to R.string.dtc_name_p0115,
        "P0116" to R.string.dtc_name_p0116,
        "P0117" to R.string.dtc_name_p0117,
        "P0118" to R.string.dtc_name_p0118,
        "P0120" to R.string.dtc_name_p0120,
        "P0121" to R.string.dtc_name_p0121,
        "P0122" to R.string.dtc_name_p0122,
        "P0123" to R.string.dtc_name_p0123,
        "P0130" to R.string.dtc_name_p0130,
        "P0131" to R.string.dtc_name_p0131,
        "P0132" to R.string.dtc_name_p0132,
        "P0133" to R.string.dtc_name_p0133,
        "P0134" to R.string.dtc_name_p0134,
        "P0135" to R.string.dtc_name_p0135,
        "P0171" to R.string.dtc_name_p0171,
        "P0172" to R.string.dtc_name_p0172,
        "P0201" to R.string.dtc_name_p0201,
        "P0230" to R.string.dtc_name_p0230,
        "P0261" to R.string.dtc_name_p0261,
        "P0262" to R.string.dtc_name_p0262,
        "P0300" to R.string.dtc_name_p0300,
        "P0301" to R.string.dtc_name_p0301,
        "P0335" to R.string.dtc_name_p0335,
        "P0336" to R.string.dtc_name_p0336,
        "P0351" to R.string.dtc_name_p0351,
        "P0500" to R.string.dtc_name_p0500,
        "P0505" to R.string.dtc_name_p0505,
        "P0506" to R.string.dtc_name_p0506,
        "P0507" to R.string.dtc_name_p0507,
        "P0508" to R.string.dtc_name_p0508,
        "P0509" to R.string.dtc_name_p0509,
        "P0511" to R.string.dtc_name_p0511,
        "P0560" to R.string.dtc_name_p0560,
        "P0562" to R.string.dtc_name_p0562,
        "P0563" to R.string.dtc_name_p0563,
        "P0601" to R.string.dtc_name_p0601,
        "P0602" to R.string.dtc_name_p0602,
        "P0627" to R.string.dtc_name_p0627,
        "P0628" to R.string.dtc_name_p0628,
        "P0629" to R.string.dtc_name_p0629,
        "P0650" to R.string.dtc_name_p0650,
        "P0657" to R.string.dtc_name_p0657,
        "P0850" to R.string.dtc_name_p0850,
    )

    /** In the app's language (read at call time). */
    fun name(code: String): String? = names[code]?.let { L.s(it) } ?: when {
        code.length == 5 && code[1] != '0' -> L.s(R.string.dtc_manufacturer)
        else -> null
    }
}
