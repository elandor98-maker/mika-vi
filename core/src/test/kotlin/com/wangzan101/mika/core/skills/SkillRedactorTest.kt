package com.wangzan101.mika.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.tool.Verb

class SkillRedactorTest {
    @Test fun literalsFromQuotesDigitsAmountsAndCriteria() {
        val lits = SkillRedactor.literals(
            "给『老妈』发「明天回家吃饭」,顺便转 5元,订单 2024 号",
            listOf("聊天里出现“明天回家吃饭”", "备注为《家人群》"),
        )
        assertEquals(setOf("老妈", "明天回家吃饭", "5元", "2024", "家人群"), lits)
        assertTrue(SkillRedactor.literals("打开微信看看", emptyList()).isEmpty())
        assertFalse("5" in SkillRedactor.literals("买 5 个", emptyList())) // 单个数字不算
        assertEquals(setOf("老妈"), SkillRedactor.literals("给「妈」和「老妈」各发一条", emptyList())) // 一个字的不换
        assertEquals("给<tham số>发", SkillRedactor.redact("给老妈发", setOf("老妈", "妈")))
    }

    @Test fun nfkcAndNumberScrub() {
        assertEquals("单号<tham số>,价格<tham số>,打开铁路12306", SkillRedactor.scrubNumbers("单号２０２４１０２３，价格35元，打开铁路12306", setOf("12306")))
        val front = "---\napps: [com.a1b2]\nlearned_from: run-2024\n---\nlearned_from: 2024"
        assertEquals("---\napps: [com.a1b2]\nlearned_from: run-2024\n---\nlearned_from: <tham số>", SkillRedactor.scrubNumbers(front, emptySet()))
        assertEquals("共<tham số>,电话 <tham số>,编号<tham số>", SkillRedactor.scrubNumbers("共1,234元,电话 138 0013 8000,编号一三八零零一三八零零零", emptySet()))
        assertEquals(setOf("王小明:明天见", "王小明", "明天见", "2024"), SkillRedactor.textLiterals(listOf(" 王小明:明天见 ", "2024")))
    }

    @Test fun redactLongestFirstAndWholeDigitRuns() {
        val md = "打开 12306,给老妈发 明天回家吃饭,第 12 个"
        val out = SkillRedactor.redact(md, setOf("12", "老妈", "明天回家", "明天回家吃饭"))
        assertEquals("打开 12306,给<tham số>发 <tham số>,第 <tham số> 个", out)
    }
}

class SkillValidateTest {
    private val ok = "---\nname: a-b\ndescription: d\n---\n## Lối vào\nx\n## Các bước\n用 `phone_tap`\n## Chỗ dễ sai\nx\n## Cách nhận biết đã xong\nx\n## Khi nào dừng lại và giao cho người dùng\nx"

    @Test fun sharedValidation() {
        assertEquals(null, SkillDrafter.validate(ok, setOf("phone_tap"), TOOLS.nameOf(Verb.RunBundledScript)))
        assertEquals("unknown_tool", SkillDrafter.validate(ok, emptySet(), TOOLS.nameOf(Verb.RunBundledScript)))
        assertEquals("invalid_name", SkillDrafter.validate(ok.replace("a-b", "A B"), setOf("phone_tap"), TOOLS.nameOf(Verb.RunBundledScript)))
        assertEquals("missing_description", SkillDrafter.validate(ok.replace("description: d", "description:"), setOf("phone_tap"), TOOLS.nameOf(Verb.RunBundledScript)))
        assertEquals("missing_section", SkillDrafter.validate(ok.replace("## Lối vào", "## Mở đầu"), setOf("phone_tap"), TOOLS.nameOf(Verb.RunBundledScript)))
        assertEquals("sensitive", SkillDrafter.validate(ok.replace("用 `phone_tap`", "支付密码是123456"), setOf("phone_tap"), TOOLS.nameOf(Verb.RunBundledScript)))
        assertEquals("too_long", SkillDrafter.validate(ok + "很".repeat(4000), setOf("phone_tap"), TOOLS.nameOf(Verb.RunBundledScript)))
    }
}
