package app.marlboroadvance.mpvex.utils.media

import app.marlboroadvance.mpvex.preferences.InMemoryPreferenceStore
import app.marlboroadvance.mpvex.preferences.SubtitleJustification
import app.marlboroadvance.mpvex.preferences.SubtitleMode
import app.marlboroadvance.mpvex.preferences.SubtitlesPreferences
import app.marlboroadvance.mpvex.ui.player.controls.components.panels.SubtitlesBorderStyle
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BilingualSubtitleParserTest {
  private lateinit var tempDir: File
  private lateinit var prefs: SubtitlesPreferences

  @Before
  fun setUp() {
    tempDir = Files.createTempDirectory("mpvex_sub_test_").toFile()
    prefs = SubtitlesPreferences(InMemoryPreferenceStore())
  }

  @After
  fun tearDown() {
    tempDir.deleteRecursively()
  }

  @Test
  fun processTextToCache_bilingualSrt_splitsCleanSrtAndFlowingAss() {
    val rawSrt = """
      1
      00:00:01,000 --> 00:00:04,500
      {\an8}只要他等我回家看他
      就会留一盏灯 就一盏
      <i>Whenever he'd be expecting me,
      he'd leave a light on. Just one.</i>

      2
      00:00:05,200 --> 00:00:08,800
      - 准备好了吗？
      - 我准备好了。
      - Are you ready?
      - I am ready.
    """.trimIndent()

    val result = BilingualSubtitleParser.processTextToCache(
      rawText = rawSrt,
      ext = "srt",
      baseName = "SlowHorses.S06E05",
      targetDir = tempDir,
      preferences = prefs,
    )

    assertNotNull(result)
    result!!
    assertTrue(result.isBilingual)

    val priText = result.primaryFile!!.readText(Charsets.UTF_8)
    val secText = result.secondaryFile!!.readText(Charsets.UTF_8)
    val biText = result.bilingualFile!!.readText(Charsets.UTF_8)

    // Primary SRT must be pure SRT: CJK wrapped without bogus extra spaces, ASS/HTML stripped
    assertFalse(priText.contains("{\\an8}"))
    assertFalse(priText.contains("<i>"))
    assertTrue(priText.contains("只要他等我回家看他就会留一盏灯 就一盏"))
    // Genuine two-speaker dashes preserved as explicit dialogue break
    assertTrue(priText.contains("- 准备好了吗？\n- 我准备好了。") || priText.contains("- 准备好了吗？\r\n- 我准备好了。"))

    // Secondary SRT must be pure SRT: English wrapped with single space, HTML stripped
    assertFalse(secText.contains("<i>"))
    assertTrue(secText.contains("Whenever he'd be expecting me, he'd leave a light on. Just one."))
    assertTrue(secText.contains("- Are you ready?\n- I am ready.") || secText.contains("- Are you ready?\r\n- I am ready."))

    // Method A flowing bilingual ASS: Default (Primary) -> \N{\rSpacer}\h\N{\rSecondary} -> Secondary
    assertTrue(biText.contains("Style: Default,"))
    assertTrue(biText.contains("Style: Secondary,"))
    assertTrue(biText.contains("Style: Spacer,"))
    assertTrue(biText.contains("\\N{\\rSpacer}\\h\\N{\\rSecondary}"))
    assertTrue(biText.contains("只要他等我回家看他就会留一盏灯 就一盏\\N{\\rSpacer}\\h\\N{\\rSecondary}Whenever he'd be expecting me, he'd leave a light on. Just one."))
    // Dialog line breaks inside Method A ASS must use \N (never raw newlines breaking Event parsing)
    assertTrue(biText.contains("- 准备好了吗？\\N- 我准备好了。\\N{\\rSpacer}\\h\\N{\\rSecondary}- Are you ready?\\N- I am ready."))
  }

  @Test
  fun processTextToCache_messyMonolingualSrt_normalizesToStandardSrt() {
    val messySrt = "\uFEFF" + """
      1
      00:00:10,000 --> 00:00:13,500
      {\an8}<b>Welcome to the future</b>
      of planetary exploration.

      2
      00:00:14,000 --> 00:00:16,500
      \u202ASecond line with\u202C <i>italic</i> formatting.
    """.trimIndent().replace("\\u202A", "\u202A").replace("\\u202C", "\u202C")

    val result = BilingualSubtitleParser.processTextToCache(
      rawText = messySrt,
      ext = "srt",
      baseName = "Oppy_en",
      targetDir = tempDir,
      preferences = prefs,
    )

    assertNotNull(result)
    result!!
    assertFalse(result.isBilingual)
    assertEquals("[标准] Oppy_en", result.primaryTitle)

    val stdText = result.primaryFile!!.readText(Charsets.UTF_8)
    assertFalse(stdText.contains("{\\an8}"))
    assertFalse(stdText.contains("<b>"))
    assertFalse(stdText.contains("<i>"))
    assertFalse(stdText.contains("\u202A"))
    assertTrue(stdText.contains("Welcome to the future of planetary exploration."))
    assertTrue(stdText.contains("Second line with italic formatting."))
  }

  @Test
  fun processTextToCache_stylizedMonolingualAss_returnsNullToPreserveFansub() {
    val fansubAss = """
      [Script Info]
      ScriptType: v4.00+
      PlayResX: 1920
      PlayResY: 1080

      [V4+ Styles]
      Format: Name, Fontname, Fontsize
      Style: OP,Arial,48

      [Events]
      Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
      Dialogue: 0,0:00:01.00,0:00:04.00,OP,,0,0,0,,{\pos(960,120)\fad(200,200)}Title Sign
      Dialogue: 0,0:00:05.00,0:00:08.00,OP,,0,0,0,,{\k25}Ka{\k30}ra{\k40}o{\k20}ke
    """.trimIndent()

    val result = BilingualSubtitleParser.processTextToCache(
      rawText = fansubAss,
      ext = "ass",
      baseName = "AnimeFansub",
      targetDir = tempDir,
      preferences = prefs,
    )

    assertNull("Stylized monolingual ASS without broken raw newlines must stay intact", result)
  }

  @Test
  fun alignCueStreams_oneToManyAndOverlapSnapping_noFlicker() {
    // 1 long Chinese line across 2 consecutive English lines + slight 150ms overlap on cue #3
    val chiCues = listOf(
      PlainSubtitleCue(1000L, 6000L, "如何试图面面俱到而不会因为需要考虑的事情之多而没了力气"),
      PlainSubtitleCue(5850L, 8000L, "下一句紧接着开始"),
    )
    val engCues = listOf(
      PlainSubtitleCue(1000L, 3500L, "How you try to be exhaustive without becoming paralyzed"),
      PlainSubtitleCue(3500L, 6000L, "by the number of things that you might have to consider."),
      PlainSubtitleCue(6100L, 8000L, "Next line begins immediately"),
    )

    val aligned = BilingualSubtitleParser.alignCueStreams(chiCues, engCues)
    assertTrue("Expected split aligned segments for 1:2 timing", aligned.size >= 3)

    // Both segments 1000..3500 and 3500..6000 keep Chinese top line visible
    val first = aligned[0]
    val second = aligned[1]
    assertTrue(first.primaryText.contains("面面俱到"))
    assertTrue(first.secondaryText.contains("exhaustive"))
    assertTrue(second.primaryText.contains("面面俱到"))
    assertTrue(second.secondaryText.contains("number of things"))

    // Verify monotonic non-overlapping snapped endMs <= next startMs
    for (i in 0 until aligned.lastIndex) {
      assertTrue(
        "Cue $i end (${aligned[i].endMs}) must not exceed cue ${i + 1} start (${aligned[i + 1].startMs})",
        aligned[i].endMs <= aligned[i + 1].startMs,
      )
    }
  }

  @Test
  fun cleanPayloadToLines_stripsMatroskaAssPrefixAndVectorDrawings() {
    val matroskaDialogue = "42,0,Default,Alice,0,0,0,,{\\i1\\pos(500,800)}Hello\\NWorld{\\i0}"
    val lines = BilingualSubtitleParser.cleanPayloadToLines(
      rawPayload = matroskaDialogue,
      stripMatroskaPrefix = true,
      isAss = true,
    )
    assertEquals(listOf("Hello", "World"), lines)

    val vectorDrawing = "15,0,Sign,,0,0,0,,{\\p1\\pos(100,100)}m 0 0 l 100 0 100 50 0 50{\\p0}"
    val emptyLines = BilingualSubtitleParser.cleanPayloadToLines(
      rawPayload = vectorDrawing,
      stripMatroskaPrefix = true,
      isAss = true,
    )
    assertTrue("ASS vector drawing coordinates must be discarded", emptyLines.isEmpty())
  }

  @Test
  fun singleAndMultiModes_areCompletelyIndependent() {
    // Customize Single mode aggressively
    prefs.font.set("SingleSerif")
    prefs.fontSize.set(72)
    prefs.subPos.set(82.0f)
    prefs.textColor.set(0xFFFF0000.toInt())
    prefs.bold.set(true)

    // Customize Dual mode differently
    prefs.multiPrimaryFont.set("DualSans")
    prefs.multiPrimaryFontSize.set(50)
    prefs.multiPrimaryTextColor.set(0xFFFFFFFF.toInt())
    prefs.multiPrimaryBorderStyle.set(SubtitlesBorderStyle.OpaqueBox)
    prefs.multiPrimaryBackgroundColor.set(0x80000000.toInt())
    prefs.multiSubPos.set(95.0f)
    prefs.multiBilingualGap.set(14)

    prefs.secondaryFont.set("SecMono")
    prefs.secondaryFontSize.set(36)
    prefs.secondaryTextColor.set(0xFFFFFF00.toInt())
    prefs.secondaryJustification.set(SubtitleJustification.Center)

    val singleOpts = BilingualSubtitleParser.buildMpvSubtitleOptions(
      preferences = prefs,
      mode = SubtitleMode.Single,
      activeTrackTitle = "[中] Video",
    ).toMap()

    assertEquals("SingleSerif", singleOpts["sub-font"])
    assertEquals("72", singleOpts["sub-font-size"])
    assertEquals("82", singleOpts["sub-pos"])
    assertEquals("yes", singleOpts["sub-bold"])
    assertEquals("#FFFF0000", singleOpts["sub-color"])
    assertEquals("", singleOpts["sub-ass-style-overrides"])

    val multiOpts = BilingualSubtitleParser.buildMpvSubtitleOptions(
      preferences = prefs,
      mode = SubtitleMode.Multi,
      activeTrackTitle = "[双语] Video",
    ).toMap()

    assertEquals("DualSans", multiOpts["sub-font"])
    assertEquals("50", multiOpts["sub-font-size"])
    assertEquals("95", multiOpts["sub-pos"])
    assertEquals("no", multiOpts["sub-bold"])
    assertEquals("#FFFFFFFF", multiOpts["sub-color"])
    assertEquals("scale", multiOpts["sub-ass-override"])

    val overrides = multiOpts["sub-ass-style-overrides"]!!
    assertTrue(overrides.contains("Default.Fontname=DualSans"))
    assertTrue(overrides.contains("Default.Fontsize=50"))
    assertTrue(overrides.contains("Default.BorderStyle=3"))
    assertTrue(overrides.contains("Default.MarginV=54")) // roundToInt((100 - 95) / 100f * 1080f) = 54
    assertTrue(overrides.contains("Secondary.Fontname=SecMono"))
    assertTrue(overrides.contains("Secondary.Fontsize=36"))
    assertTrue(overrides.contains("Secondary.PrimaryColour=&H0000FFFF")) // Yellow #FFFFFF00 -> ASS &H0000FFFF
    assertTrue(overrides.contains("Spacer.Fontsize=14"))
  }
}
