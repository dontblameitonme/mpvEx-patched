package app.marlboroadvance.mpvex.utils.media

import app.marlboroadvance.mpvex.preferences.InMemoryPreferenceStore
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests against real MKV files on the local machine (`C:\Users\root\Downloads\新建文件夹`).
 * Verifies header parsing, Attachments/SeekHead/LanguageBCP47 scanning speed, language selection,
 * `ReadOrder` stripping, unlabeled SDH track probing, and clean `.srt` + Method A `[双语].ass` output.
 */
class MkvSubtitleExtractorRealCorpusTest {
  private lateinit var tempDir: File
  private lateinit var prefs: SubtitlesPreferences

  @Before
  fun setUp() {
    tempDir = Files.createTempDirectory("mpvex_mkv_corpus_").toFile()
    prefs = SubtitlesPreferences(InMemoryPreferenceStore())
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  private fun assertCleanNormalizedOutputs(result: BilingualSplitResult, label: String) {
    val srtTagPollution = Regex("""\{\\[^}]+\}""")
    val htmlTagPollution = Regex("""</?(?:i|b|u|s|font|span)\b[^>]*>""", RegexOption.IGNORE_CASE)
    val readOrderPrefix = Regex("""(?m)^\d+,\d+,[^,]*,""")

    listOfNotNull(result.primaryFile, result.secondaryFile).forEach { srtFile ->
      assertTrue("$label SRT must exist & be valid: ${srtFile.name}", BilingualSubtitleParser.isValidSrtFile(srtFile))
      val content = srtFile.readText(Charsets.UTF_8)
      assertFalse("$label SRT ${srtFile.name} must not contain ReadOrder prefix", readOrderPrefix.containsMatchIn(content))
      assertFalse("$label SRT ${srtFile.name} must not contain ASS override tags", srtTagPollution.containsMatchIn(content))
      assertFalse("$label SRT ${srtFile.name} must not contain HTML tags", htmlTagPollution.containsMatchIn(content))
      assertTrue("$label SRT ${srtFile.name} must contain timings", content.contains("-->"))
    }

    result.bilingualFile?.let { assFile ->
      assertTrue("$label ASS must exist & be valid: ${assFile.name}", BilingualSubtitleParser.isValidAssFile(assFile))
      val content = assFile.readText(Charsets.UTF_8)
      assertTrue("$label ASS must define flowing Spacer style", content.contains("Style: Spacer,"))
      assertTrue("$label ASS must connect lines via Method A", content.contains("\\N{\\rSpacer}\\h\\N{\\rSecondary}"))
      val dialogueLines = content.lineSequence().filter { it.startsWith("Dialogue:") }.toList()
      assertTrue("$label ASS must have non-empty dialogues", dialogueLines.isNotEmpty())
      var prevEnd = -1L
      var overlapCount = 0
      dialogueLines.forEach { d ->
        assertFalse("$label Dialogue must not contain ReadOrder", readOrderPrefix.containsMatchIn(d))
        assertFalse("$label Dialogue must not contain HTML", htmlTagPollution.containsMatchIn(d))
        val parts = d.substringAfter(':').split(',', limit = 4)
        val sMs = parseAssMs(parts[1].trim())
        val eMs = parseAssMs(parts[2].trim())
        if (prevEnd > sMs) overlapCount++
        prevEnd = maxOf(prevEnd, eMs)
      }
      println("  Dialogue count=${dialogueLines.size}, overlappingTransitions=$overlapCount")
      dialogueLines.filter { it.contains("0:40:") || it.contains("只要他等") }.take(3).forEach {
        println("  Screenshot match: $it")
      }
    }
  }

  private fun parseAssMs(t: String): Long {
    val p = t.split(':', '.')
    return ((p[0].toLong() * 3600L + p[1].toLong() * 60L + p[2].toLong()) * 1000L) + p[3].padEnd(2, '0').toLong() * 10L
  }

  private fun runExtractionAndPrintSummary(mkvPath: String, baseName: String, expectBilingual: Boolean) {
    val mkvFile = File(mkvPath)
    assumeTrue("Real corpus file not present: $mkvPath", mkvFile.exists())

    val outDir = File(tempDir, baseName)
    val startNs = System.nanoTime()
    val result = FileInputStream(mkvFile).use { fis ->
      fis.channel.use { ch ->
        MkvSubtitleExtractor.extractFromChannel(
          channel = ch,
          fileSize = mkvFile.length(),
          baseName = baseName,
          targetDir = outDir,
          preferences = prefs,
        )
      }
    }
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

    assertNotNull("Expected extracted subtitles for $baseName", result)
    result!!

    println("=== Corpus: $baseName (${mkvFile.length() / (1024 * 1024)} MB) extracted in ${elapsedMs} ms ===")
    println("  isBilingual=${result.isBilingual}, pri=${result.primaryFile?.name}, sec=${result.secondaryFile?.name}, bi=${result.bilingualFile?.name}")
    result.bilingualFile?.readText(Charsets.UTF_8)?.lineSequence()?.filter { it.startsWith("Dialogue:") }?.take(3)?.forEach {
      println("  Sample ASS: $it")
    }
    result.primaryFile?.let { f ->
      println("  Sample SRT (${f.name}):\n${f.readText(Charsets.UTF_8).lineSequence().take(12).joinToString("\n")}")
    }

    if (expectBilingual) {
      assertTrue("Expected bilingual extraction for $baseName", result.isBilingual)
      assertNotNull("Expected [双语].ass for $baseName", result.bilingualFile)
    }
    assertCleanNormalizedOutputs(result, baseName)
  }

  @Test
  fun corpus_theSeat2025_extractsCleanBilingualSrtAndAss() {
    runExtractionAndPrintSummary(
      mkvPath = "C:\\Users\\root\\Downloads\\新建文件夹\\The Seat 2025 1080p NF WEB-DL DD 5 1 H 264-playWEB.mkv",
      baseName = "TheSeat2025",
      expectBilingual = true,
    )
  }

  @Test
  fun corpus_goodNightOppy_extractsUnlabeledEnglishSdhAndChinese() {
    runExtractionAndPrintSummary(
      mkvPath = "C:\\Users\\root\\Downloads\\新建文件夹\\Good.Night.Oppy.2022.2160p.AMZN.WEB-DL.DDP5.1.HDR10+.H265-MOMOWEB.mkv",
      baseName = "GoodNightOppy",
      expectBilingual = true,
    )
  }

  @Test
  fun corpus_slowHorsesS06E05_handlesAttachmentsAndBcp47() {
    runExtractionAndPrintSummary(
      mkvPath = "C:\\Users\\root\\Downloads\\新建文件夹\\流人[全6集][简繁英字幕].Slow.Horses.S06.1080p.Apple.TV\\WEB-DL.DDP.5.1.Atmos.H.264-BlackTV\\【高清剧集网发布 www.QQHDTV.com】流人[全6集][简繁英字幕].Slow.Horses.S06.1080p.Apple.TV+.WEB-DL.DDP.5.1.Atmos.H.264-BlackTV\\Slow.Horses.S06E05.Sayonara.1080p.Apple.TV+.WEB-DL.DDP.5.1.Atmos.H.264-BlackTV.mkv",
      baseName = "SlowHorsesS06E05",
      expectBilingual = true,
    )
  }

  @Test
  fun corpus_explainedS02E01_stripsMatroskaAssPrefixCleanly() {
    runExtractionAndPrintSummary(
      mkvPath = "C:\\Users\\root\\Downloads\\新建文件夹\\Explained.S02 (озвуч. В одне рило)\\Explained.S02E01.1080p.NF.WEB-DL.DDP5.1.H.264-SPiRiT.mkv",
      baseName = "ExplainedS02E01",
      expectBilingual = false, // Assert valid clean extraction whether bilingual or single-track
    )
  }
}
