package com.tgfinder.search

import java.util.Locale

/** What we could extract from a release-style file name such as "The.Matrix.1999.1080p.BluRay.x264-GRP.mkv". */
data class ParsedName(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val quality: String? = null,
    val source: String? = null,
    val codec: String? = null,
) {
    val isTv: Boolean get() = season != null

    /** "S01E02", "S01" or null. */
    val episodeLabel: String?
        get() = when {
            season == null -> null
            episode == null -> "S%02d".format(season)
            else -> "S%02dE%02d".format(season, episode)
        }
}

object FileNameParser {

    private val EXTENSION = Regex("""\.(mkv|mp4|avi|mov|webm|m4v|ts|wmv|flv|mpg|mpeg|3gp)$""", RegexOption.IGNORE_CASE)
    private val URL = Regex("""(https?://\S+|www\.\S+|\b\S+\.(com|net|org|me|in|to|mx|io|cc|xyz|lol|site)\b)""", RegexOption.IGNORE_CASE)
    private val HANDLE = Regex("""@\w+""")
    private val BRACKETED = Regex("""[\[{【][^\]}】]*[\]}】]""")

    private val QUALITY = Regex("""\b(4320p|2160p|1440p|1080p|1080i|720p|576p|480p|360p|240p|4k|uhd|8k)\b""", RegexOption.IGNORE_CASE)
    private val SOURCE = Regex(
        """\b(web[ -]?dl|webrip|web|blu[ -]?ray|bdrip|brrip|bdremux|remux|hdrip|dvdrip|dvdscr|hdtv|pdtv|hdcam|camrip|cam|hdts|telesync|ts ?rip|hdtc|predvd)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CODEC = Regex("""\b(x ?264|x ?265|h ?\.?264|h ?\.?265|hevc|avc|av1|xvid|divx|vp9)\b""", RegexOption.IGNORE_CASE)

    private val SEASON_EPISODE = listOf(
        Regex("""\bS(\d{1,2}) ?[-_. ]? ?E(\d{1,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{1,2})x(\d{2,3})\b""", RegexOption.IGNORE_CASE),
        Regex("""\bSeason ?(\d{1,2}) ?(?:Episode|Ep|E) ?(\d{1,3})\b""", RegexOption.IGNORE_CASE),
    )
    private val SEASON_ONLY = listOf(
        Regex("""\bS(\d{1,2})\b(?! ?E\d)""", RegexOption.IGNORE_CASE),
        Regex("""\bSeason ?(\d{1,2})\b""", RegexOption.IGNORE_CASE),
    )
    private val EPISODE_ONLY = Regex("""\b(?:Ep|Episode|E)[ .]?(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""(?<![0-9])(19[2-9]\d|20[0-4]\d)(?![0-9])""")

    /** Words that only ever appear after the title in release names. */
    private val JUNK_WORDS = setOf(
        "hdr", "hdr10", "hdr10+", "dv", "dovi", "sdr", "10bit", "8bit", "12bit", "imax", "proper", "repack", "rerip",
        "extended", "unrated", "uncut", "directors", "remastered", "limited", "internal", "complete", "multi",
        "dual", "audio", "dubbed", "subbed", "esub", "esubs", "msub", "msubs", "sub", "subs",
        "hindi", "english", "eng", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi", "punjabi",
        "korean", "japanese", "spanish", "french", "german", "italian", "russian", "chinese", "urdu",
        "org", "hq", "hc", "aac", "aac2", "ac3", "eac3", "dd", "ddp", "dd5", "ddp5", "dts", "truehd", "atmos", "flac",
        "opus", "mp3", "5.1", "7.1", "2.0", "nf", "amzn", "dsnp", "hmax", "atvp", "hulu", "zee5", "jc", "jio",
        "hs", "hotstar", "sonyliv", "mx", "aha", "x264", "x265", "hevc", "avc", "rip", "yts", "yify", "rarbg",
        "psa", "galaxyrg", "tigole", "qxr", "pahe", "mkvcage", "vegamovies", "katmoviehd", "moviesmod", "mkvcinemas",
    )

    fun parse(fileName: String, caption: String? = null): ParsedName {
        val base = fileName.trim()
        val fromFile = if (looksGeneric(base)) null else parseInternal(base)
        if (fromFile != null && fromFile.title.isNotBlank()) {
            return fromFile
        }
        val captionLine = caption?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotBlank() }
        if (captionLine != null) {
            val fromCaption = parseInternal(captionLine)
            if (fromCaption.title.isNotBlank()) {
                // Keep technical details from the file name when the caption lacks them.
                return fromCaption.copy(
                    quality = fromCaption.quality ?: fromFile?.quality,
                    source = fromCaption.source ?: fromFile?.source,
                    codec = fromCaption.codec ?: fromFile?.codec,
                )
            }
        }
        return fromFile ?: ParsedName(title = base.replace(EXTENSION, ""))
    }

    /** Names like "video_2023-04-01_12-00-00.mp4" or "1234567.mp4" carry no title. */
    private fun looksGeneric(name: String): Boolean {
        val n = name.replace(EXTENSION, "").lowercase(Locale.ROOT)
        return n.isBlank() || n.matches(Regex("""(video|vid|file|document|telegram|movie)?[_ -]*[\d_ :.-]*""")) ||
            n.matches(Regex("""(img|vid|video|mov)[_-]\d+.*"""))
    }

    private fun parseInternal(raw: String): ParsedName {
        var s = raw.replace(EXTENSION, "")
        s = s.replace(URL, " ").replace(HANDLE, " ")

        // Remove bracketed groups ([YTS.MX], {Team}) but keep a bracketed year: "Movie (2019)" / "[2019]".
        s = BRACKETED.replace(s) { m -> YEAR.find(m.value)?.let { " ${it.value} " } ?: " " }
        s = s.replace('(', ' ').replace(')', ' ')

        // Normalise separators. Keep "H.264" / "5.1" style tokens intact first.
        s = s.replace(Regex("""(?i)\bh\.(26[45])"""), "h$1")
        s = s.replace(Regex("""(?<![0-9])(\d)\.(\d)(?![0-9])"""), "$1#$2")
        s = s.replace('.', ' ').replace('_', ' ').replace('#', '.')
        s = s.replace(Regex("""\s+"""), " ").trim()

        val quality = QUALITY.find(s)?.value?.let(::normaliseQuality)
        val source = SOURCE.find(s)?.value?.let(::normaliseSource)
        val codec = CODEC.find(s)?.value?.let(::normaliseCodec)

        var season: Int? = null
        var episode: Int? = null
        var seIndex = Int.MAX_VALUE
        for (r in SEASON_EPISODE) {
            val m = r.find(s) ?: continue
            season = m.groupValues[1].toInt()
            episode = m.groupValues[2].toInt()
            seIndex = m.range.first
            break
        }
        if (season == null) {
            for (r in SEASON_ONLY) {
                val m = r.find(s) ?: continue
                season = m.groupValues[1].toInt()
                seIndex = m.range.first
                break
            }
            if (season != null) {
                EPISODE_ONLY.find(s, seIndex)?.let { episode = it.groupValues[1].toInt() }
            }
        }

        // The year is usually the last 4-digit year that is not the very first token
        // ("2001 A Space Odyssey 1968" -> 1968, "1917 2019" -> 2019, "1917" -> no year).
        val years = YEAR.findAll(s).toList()
        val yearMatch = years.lastOrNull { it.range.first > 0 && it.range.first < seIndex.coerceAtMost(s.length) }
            ?: years.lastOrNull { it.range.first > 0 }
        val year = yearMatch?.value?.toInt()

        val cutCandidates = mutableListOf<Int>()
        yearMatch?.let { cutCandidates += it.range.first }
        if (seIndex != Int.MAX_VALUE) cutCandidates += seIndex
        listOf(QUALITY, SOURCE, CODEC).forEach { r -> r.find(s)?.let { if (it.range.first > 0) cutCandidates += it.range.first } }
        firstJunkIndex(s)?.let { cutCandidates += it }
        val cut = cutCandidates.filter { it > 0 }.minOrNull() ?: s.length

        var title = s.substring(0, cut)
        title = title.replace(Regex("""[-–—:|,+~]+\s*$"""), "")
            .replace(Regex("""^\s*[-–—:|,+~]+"""), "")
            .replace(Regex("""\s+-\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        title = prettifyCase(title)

        return ParsedName(title = title, year = year, season = season, episode = episode, quality = quality, source = source, codec = codec)
    }

    private fun firstJunkIndex(s: String): Int? {
        var index = 0
        var first = true
        for (token in s.split(' ')) {
            val t = token.lowercase(Locale.ROOT).trim('-', '+', ',', '[', ']')
            val bare = t.substringBefore('-')
            if (!first && (t in JUNK_WORDS || bare in JUNK_WORDS)) return index
            first = false
            index += token.length + 1
        }
        return null
    }

    private fun prettifyCase(t: String): String {
        val letters = t.filter { it.isLetter() }
        if (letters.isEmpty()) return t
        val allLower = letters.all { it.isLowerCase() }
        val allUpper = letters.length > 3 && letters.all { it.isUpperCase() }
        if (!allLower && !allUpper) return t
        return t.lowercase(Locale.ROOT).split(' ').joinToString(" ") { w ->
            w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }

    private fun normaliseQuality(q: String): String = when (q.lowercase(Locale.ROOT)) {
        "4k", "uhd", "2160p" -> "4K"
        "8k", "4320p" -> "8K"
        "1080i" -> "1080p"
        else -> q.lowercase(Locale.ROOT)
    }

    private fun normaliseSource(s: String): String {
        val l = s.lowercase(Locale.ROOT).replace(" ", "").replace("-", "")
        return when {
            l.startsWith("webdl") -> "WEB-DL"
            l == "webrip" -> "WEBRip"
            l == "web" -> "WEB"
            l.startsWith("bluray") -> "BluRay"
            l == "bdrip" || l == "brrip" -> "BRRip"
            l.contains("remux") -> "REMUX"
            l == "hdrip" -> "HDRip"
            l == "dvdrip" -> "DVDRip"
            l == "dvdscr" -> "DVDScr"
            l == "hdtv" || l == "pdtv" -> "HDTV"
            l.contains("cam") -> "CAM"
            l == "hdts" || l == "telesync" || l.startsWith("ts") -> "TS"
            else -> s.uppercase(Locale.ROOT)
        }
    }

    private fun normaliseCodec(c: String): String {
        val l = c.lowercase(Locale.ROOT).replace(" ", "").replace(".", "")
        return when (l) {
            "x265", "h265", "hevc" -> "HEVC"
            "x264", "h264", "avc" -> "H.264"
            "av1" -> "AV1"
            else -> c.uppercase(Locale.ROOT)
        }
    }

    /** Title key used to group duplicates and to cache TMDB lookups. */
    fun normalizedKey(title: String): String =
        title.lowercase(Locale.ROOT)
            .replace("&", "and")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""^(the|a|an) """), "")
            .trim()
}
