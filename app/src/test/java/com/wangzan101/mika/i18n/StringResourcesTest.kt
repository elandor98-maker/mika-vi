package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Bản Mika VI chỉ còn hai ngôn ngữ giao diện: tiếng Anh (mặc định, `values/`) và
 * tiếng Việt (`values-vi/`). Hai bộ phải khớp nhau từng khóa và từng ký tự giữ chỗ;
 * tiếng Việt theo CLDR chỉ có một dạng số nhiều "other".
 */
class StringResourcesTest {
    /** Một ngôn ngữ ngoài tiếng Anh: thư mục tài nguyên và các dạng số nhiều CLDR của nó. */
    private class Other(val folder: String, val locale: Locale, val quantities: Set<String>) {
        val texts by lazy { ResFileTexts(folder, locale) }
    }

    private val others = listOf(
        Other("values-vi", Locale.forLanguageTag("vi"), setOf("other")),
    )

    private val en = ResFileTexts.english

    /** Các mục translatable="false" chỉ nằm ở file mặc định. */
    private val fixed: Set<String> = ResFileTexts.resFiles("values").flatMap { f ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }
    }.toSet()

    @Test
    fun vietnameseCoversEnglish() {
        val vi = others.single().texts
        assertEquals(en.strings.keys - fixed, vi.strings.keys)
        assertEquals(en.plurals.keys, vi.plurals.keys)
        assertTrue(en.strings.size > 300)
    }

    @Test
    fun otherLanguagesMatchEnglish() {
        for (o in others) {
            val t = o.texts
            assertEquals(o.folder, en.strings.keys - fixed, t.strings.keys)
            assertEquals(o.folder, en.plurals.keys, t.plurals.keys)
            for (name in t.strings.keys - setOf("date_month_day", "date_year_month_day")) {
                assertEquals("${o.folder}/$name", args(en.strings.getValue(name)), args(t.strings.getValue(name)))
            }
            for (name in t.plurals.keys) {
                val forms = t.plurals.getValue(name)
                assertEquals("${o.folder}/$name", o.quantities, forms.keys)
                val expected = args(en.plurals.getValue(name).getValue("other"))
                forms.forEach { (q, v) ->
                    assertEquals("${o.folder}/$name/$q", expected, args(v))
                }
            }
        }
    }

    /** Tiếng Việt không được sót chữ Hán chưa dịch. */
    @Test
    fun otherLanguagesHaveNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        for (o in others) {
            val left = o.texts.strings.filter { (_, v) -> cjk.containsMatchIn(v) }.keys +
                o.texts.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
            assertEquals(o.folder, emptySet<String>(), left)
        }
    }

    /** Mọi ngôn ngữ trong AppLanguage đều có thư mục tài nguyên tương ứng, ui_locale khớp. */
    @Test
    fun everyAppLanguageHasItsTexts() {
        val byLanguage = others.associateBy { AppLanguage.modernCode(it.locale.language) }
        for (l in AppLanguage.entries) {
            val code = l.language ?: continue
            if (code == "en") continue
            val o = byLanguage[code]
            assertTrue("$l has no folder", o != null)
            assertEquals(l.toString(), code, AppLanguage.modernCode(Locale.forLanguageTag(o!!.texts.strings.getValue("ui_locale")).language))
        }
    }

    @Test
    fun englishHasNoChineseLeftOver() {
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val left = en.strings.filter { (k, v) -> k !in fixed && cjk.containsMatchIn(v) }.keys +
            en.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
        assertEquals(emptySet<String>(), left)
    }

    @Test
    fun languageFromTag() {
        val likely = { tag: String -> if (tag == "zh-x-hant") "Hant" else "Hans" }
        fun of(tag: String?) = AppLanguage.fromTag(tag, likely)
        assertEquals(AppLanguage.System, of(null))
        assertEquals(AppLanguage.System, of(""))
        assertEquals(AppLanguage.Vietnamese, of("vi"))
        assertEquals(AppLanguage.Vietnamese, of("vi-VN"))
        assertEquals(AppLanguage.English, of("en"))
        assertEquals(AppLanguage.English, of("fr-FR"))
        // Các ngôn ngữ đã bỏ trong bản VI: rơi về tiếng Anh
        assertEquals(AppLanguage.English, of("zh-Hans"))
        assertEquals(AppLanguage.English, of("ja-JP"))
    }

    @Test
    fun eachLanguageHasItsOwnTag() {
        for (l in AppLanguage.entries) assertEquals(l, AppLanguage.fromTag(l.tag) { "Hans" })
    }

    private fun args(s: String): Set<String> = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value }.toSet()
}
