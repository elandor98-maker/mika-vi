package com.wangzan101.mika.memory

import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.skills.PersonalSkillFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/**
 * Gói huấn luyện nạp sẵn (v3.3.1): lần đầu ghi đủ 4 sự kiện + 3 kỹ năng qua đúng cửa ghi của hệ thống,
 * có cờ nên chạy lại không ghi trùng và không «hồi sinh» mục người dùng đã xoá.
 */
class SeedPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val now = 1_781_000_000_000L
    private val ids = AtomicInteger(0)

    private fun store() = MemoryStore(
        EncryptedMemoryFile(tmp.root, AesGcmKeyCipher { key }) { now },
        { now },
        { "seed-test-${ids.incrementAndGet()}" },
    )

    private fun skills() = PersonalSkillFiles(tmp.root)
    private val flag get() = File(tmp.root, SeedPack.FLAG_NAME)

    @Test fun firstRunSeedsFactsAndSkills() {
        val store = store()
        val skills = skills()
        assertTrue(SeedPack.seedIfNeeded(store, skills, flag, now))
        assertTrue(flag.exists())

        val items = store.items.value
        assertEquals(4, items.size)
        assertTrue(items.all { it.status == MemoryStatus.active })
        assertTrue(items.any { it.content.contains("Xiaomi 17T Pro") })
        assertTrue(items.any { it.content.contains("luôn trả lời bằng tiếng Việt") })
        assertTrue(items.any { it.content.contains("chỉ dùng các AI miễn phí") })
        assertTrue(items.any { it.content.contains("đối chiếu ít nhất 2 nguồn") })

        val seeded = skills.list()
        assertEquals(listOf("bat-tat-den-pin", "bat-tat-wifi-bluetooth", "mo-ung-dung-theo-ten"), seeded.map { it.name }.sorted())
        // Nạp qua put() (kỹ năng đã nhận) chứ không phải putPending(): mục Chờ duyệt phải trống,
        // trạng thái là trial như kỹ năng vừa được nhận theo cơ chế gốc.
        assertTrue(skills.pending().isEmpty())
        assertTrue(seeded.all { it.status == SkillStatus.trial })
        assertTrue(seeded.single { it.name == "bat-tat-den-pin" }.markdown.contains("Đèn pin"))
    }

    @Test fun secondRunNeverResurrectsDeletedEntries() {
        val store = store()
        val skills = skills()
        assertTrue(SeedPack.seedIfNeeded(store, skills, flag, now))
        val fact = store.items.value.first { it.content.contains("Xiaomi 17T Pro") }
        assertTrue(store.forget(fact.id).isSuccess)
        skills.remove("bat-tat-den-pin")

        assertTrue(SeedPack.seedIfNeeded(store, skills, flag, now))
        assertEquals(3, store.items.value.size)
        assertNull(skills.get("bat-tat-den-pin"))
        assertEquals(2, skills.list().size)
    }

    @Test fun lostFlagDoesNotDuplicate() {
        val store = store()
        val skills = skills()
        assertTrue(SeedPack.seedIfNeeded(store, skills, flag, now))
        flag.delete()
        // Cờ mất nhưng dữ liệu còn: nhận ra theo nội dung/tên, không ghi trùng.
        assertTrue(SeedPack.seedIfNeeded(store, skills, flag, now))
        assertEquals(4, store.items.value.size)
        assertEquals(3, skills.list().size)
        assertFalse(store.items.value.map { it.content }.toSet().size != 4)
    }
}
