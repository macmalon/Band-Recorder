package com.bandrecorder.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordingFileNaming {
    private val sessionDateFormat = SimpleDateFormat("ddMMyy_HHmmss", Locale.US)
    private val morceauRegex = Regex("""^(session_(?:\d{6}|\d{8})_\d{6})_.*_morceau_(\d{2})\.wav$""")
    // Pattern to catch the date part: session_ddMMyy_HHmmss... or session_ddMMyyyy_HHmmss...
    private val sessionDateRegex = Regex("""^session_(\d{6,8})_(\d{6}).*$""")

    fun sessionBaseName(now: Date = Date()): String = "session_${sessionDateFormat.format(now)}"

    fun rawFileName(sessionBaseName: String): String = "${sessionBaseName}_raw.wav"

    fun cleanFileName(sessionBaseName: String): String = "${sessionBaseName}_clean.wav"

    fun morceauFileName(sessionBaseName: String, morceauIndex: Int): String {
        val safe = morceauIndex.coerceAtLeast(1)
        return "${sessionBaseName}_morceau_${"%02d".format(Locale.US, safe)}.wav"
    }

    fun cleanMetadataFileName(sessionBaseName: String): String = "${sessionBaseName}_clean.meta.json"

    fun userVisibleTitle(fileName: String): String {
        val parsedMorceau = parseMorceau(fileName)
        if (parsedMorceau != null) {
            return "Morceau ${parsedMorceau.morceauIndex}"
        }

        val match = sessionDateRegex.matchEntire(fileName) ?: return fileName
        val datePart = match.groupValues[1]
        val timePart = match.groupValues[2]

        return try {
            val date = if (datePart.length == 6) {
                SimpleDateFormat("ddMMyy", Locale.US).parse(datePart)
            } else {
                SimpleDateFormat("ddMMyyyy", Locale.US).parse(datePart)
            }
            val time = SimpleDateFormat("HHmmss", Locale.US).parse(timePart)
            
            val outDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(date!!)
            val outTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(time!!)
            
            "$outDate $outTime"
        } catch (e: Exception) {
            fileName
        }
    }

    fun segmentSortKey(fileName: String): Int {
        val parsed = parseMorceau(fileName) ?: return Int.MAX_VALUE
        return parsed.morceauIndex
    }

    private fun parseMorceau(fileName: String): ParsedMorceau? {
        val match = morceauRegex.matchEntire(fileName) ?: return null
        val index = match.groupValues[2].toIntOrNull() ?: return null
        return ParsedMorceau(sessionBase = match.groupValues[1], morceauIndex = index)
    }

    private data class ParsedMorceau(
        val sessionBase: String,
        val morceauIndex: Int
    )
}
