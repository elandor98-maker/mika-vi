package com.wangzan101.mika.memory

import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillStatus
import java.io.File

/**
 * Gói «huấn luyện» nạp sẵn (Phòng AI v3.3.1): bộ nhớ của app lúc mới cài vốn trống trơn —
 * hệ thống gốc chỉ học từ các tác vụ ĐÃ làm xong, tác vụ hỏng thì không học được gì, nên
 * người dùng mở màn hình Ký ức chỉ thấy «Chưa ghi nhớ gì». Gói này ghi sẵn một ít sự kiện
 * và kỹ năng nền đã biết chắc về người dùng/máy của họ, để ngay từ tác vụ đầu tiên app
 * đã có sẵn ngữ cảnh và cách làm đúng cho các việc hay gặp nhất.
 *
 * Quy tắc an toàn:
 * - Chỉ chạy ĐÚNG MỘT LẦN: sau khi ghi xong thì để lại file cờ [FLAG_NAME] trong thư mục
 *   dữ liệu của app; các lần mở app sau thấy cờ là thôi. Người dùng sửa/xoá các mục đã nạp
 *   thì KHÔNG bao giờ bị ghi lại (cờ còn đó), và bản thân hàm cũng bỏ qua mục nào đã tồn tại.
 * - Sự kiện đi qua [MemoryStore.add] — đúng cửa ghi mà bộ trích xuất bộ nhớ dùng — nên định
 *   dạng, kiểm duyệt nội dung nhạy cảm và mã hoá khi lưu đều là của hệ thống. Nguồn ghi là
 *   `manual` (mục nhập sẵn, người dùng sửa/xoá tự do như mục tự ghi; không giả mạo trích
 *   nguyên văn lời người dùng). Kind là fact/preference nên trạng thái là active ngay
 *   (procedure qua cửa này sẽ rơi vào chờ duyệt — không dùng cho gói nạp sẵn).
 * - Kỹ năng đi qua [PersonalSkills.put] — đúng cơ chế lưu kỹ năng ĐÃ được nhận — nên chúng
 *   nằm ở mục Kỹ năng đang dùng, KHÔNG nằm ở «Chờ duyệt» (đó là chỗ của bản nháp do agent
 *   tự rút ra từ tác vụ, qua putPending). Trạng thái là [SkillStatus.trial] — đúng trạng
 *   thái hệ thống gán cho kỹ năng vừa được nhận mà chưa làm thành việc nào (xem approve()
 *   trong PersonalSkillFiles); dùng tốt sẽ tự lên active theo cơ chế sẵn có. Nguồn ghi là
 *   [PersonalSkill.SOURCE_IMPORTED] (đi kèm app, không phải agent tự học).
 * - Ghi không trọn (ví dụ khoá mã hoá lúc khởi động chưa sẵn sàng, MemoryStore báo
 *   unavailable): KHÔNG để cờ, lần mở app sau thử lại; các mục đã ghi rồi được nhận ra
 *   theo nội dung/tên nên không ghi trùng.
 */
object SeedPack {
    const val FLAG_NAME = "seed_pack_v1_done"

    /** Một sự kiện nạp sẵn: nội dung + từ khoá để truy hồi. */
    private data class SeedFact(val kind: MemoryKind, val content: String, val keywords: List<String>)

    private val FACTS = listOf(
        SeedFact(
            MemoryKind.preference,
            "Người dùng là chủ máy; gọi người dùng là \"anh\", luôn trả lời bằng tiếng Việt.",
            listOf("xưng hô", "tiếng Việt", "chủ máy"),
        ),
        SeedFact(
            MemoryKind.fact,
            "Điện thoại của người dùng: Xiaomi 17T Pro chạy HyperOS (Android 16).",
            listOf("điện thoại", "Xiaomi 17T Pro", "HyperOS"),
        ),
        SeedFact(
            MemoryKind.preference,
            "Người dùng chỉ dùng các AI miễn phí; khi một nhà cung cấp hết lượt, tự chuyển nhà khác, không dừng việc giữa chừng.",
            listOf("miễn phí", "hết lượt", "chuyển nhà cung cấp"),
        ),
        SeedFact(
            MemoryKind.preference,
            "Việc bật/tắt đơn giản phải đi đường ngắn nhất qua giao diện; thông tin biến động như giá tiền số phải đối chiếu ít nhất 2 nguồn trước khi khẳng định.",
            listOf("bật tắt", "đường ngắn nhất", "giá tiền số", "đối chiếu 2 nguồn"),
        ),
    )

    private fun skill(name: String, display: String, description: String, terms: List<String>, body: String, now: Long) =
        PersonalSkill(
            name = name,
            markdown = buildString {
                append("---\n")
                append("name: $name\n")
                append("display: $display\n")
                append("description: $description\n")
                append("terms: [${terms.joinToString(", ") { "\"$it\"" }}]\n")
                append("---\n")
                append(body.trim())
                append("\n")
            },
            status = SkillStatus.trial,
            learnedFrom = null,
            replacesDraftOf = null,
            createdAt = now,
            outcomes = emptyList(),
            source = PersonalSkill.SOURCE_IMPORTED,
        )

    private fun skills(now: Long) = listOf(
        skill(
            name = "bat-tat-den-pin",
            display = "Bật/tắt đèn pin",
            description = "Bật hoặc tắt đèn pin trên máy HyperOS bằng ô Đèn pin trong bảng cài đặt nhanh, đường ngắn nhất",
            terms = listOf("đèn pin", "flashlight", "bật đèn pin", "tắt đèn pin"),
            body = """
                ## Cách làm

                1. Trên màn hình tác vụ, mở bảng cài đặt nhanh (vuốt từ cạnh trên màn hình xuống để mở bảng thông báo/cài đặt nhanh của HyperOS).
                2. Chạm ô «Đèn pin» một lần để đổi trạng thái.
                3. Kiểm tra trạng thái đã đổi đúng như yêu cầu (ô Đèn pin sáng lên khi đang bật, đèn sau máy sáng/tắt theo).
                4. Đạt trạng thái xong thì DỪNG NGAY và báo lại cho người dùng.

                ## Không làm

                - Không dùng dumpsys, không soi service/process/receiver/log hệ thống để bật đèn pin.
                - Chỉ khi đường trực tiếp trên đây thất bại ít nhất 2 lần mới chuyển sang chẩn đoán bằng shell/hệ thống.
            """,
            now = now,
        ),
        skill(
            name = "bat-tat-wifi-bluetooth",
            display = "Bật/tắt Wi-Fi và Bluetooth",
            description = "Bật hoặc tắt Wi-Fi, Bluetooth bằng đúng ô tương ứng trong bảng cài đặt nhanh",
            terms = listOf("wifi", "wi-fi", "bluetooth", "bật wifi", "tắt bluetooth"),
            body = """
                ## Cách làm

                1. Trên màn hình tác vụ, mở bảng cài đặt nhanh (vuốt từ cạnh trên màn hình xuống).
                2. Chạm đúng ô «Wi-Fi» hoặc «Bluetooth» cần đổi trạng thái (chạm nhầm ô là đổi nhầm thứ — nhìn kỹ nhãn/icon của ô trước khi chạm).
                3. Xác nhận trạng thái mới của ô (màu/nét của ô và dòng trạng thái bên dưới tên ô).
                4. Đạt trạng thái xong thì DỪNG NGAY và báo lại cho người dùng.

                ## Không làm

                - Không vòng qua ứng dụng Cài đặt hay lệnh shell khi ô cài đặt nhanh đang dùng được.
                - Chỉ khi đường trực tiếp thất bại ít nhất 2 lần mới chuyển sang đường khác.
            """,
            now = now,
        ),
        skill(
            name = "mo-ung-dung-theo-ten",
            display = "Mở ứng dụng theo tên",
            description = "Mở một ứng dụng người dùng gọi tên bằng thao tác mở ứng dụng trực tiếp",
            terms = listOf("mở ứng dụng", "mở app", "khởi chạy"),
            body = """
                ## Cách làm

                1. Dùng thao tác mở ứng dụng trực tiếp với tên/gói ứng dụng người dùng yêu cầu.
                2. Chờ ứng dụng hiện lên trên màn hình tác vụ và xác nhận đúng ứng dụng đó đang ở trước mắt.
                3. Xong thì DỪNG NGAY và báo lại; chỉ làm tiếp khi người dùng yêu cầu thao tác trong ứng dụng.

                ## Không làm

                - Không vòng qua Cài đặt, không tìm kiếm trong hệ thống, không mở ngăn kéo ứng dụng để mò bằng tay khi thao tác mở trực tiếp đang dùng được.
            """,
            now = now,
        ),
    )

    /**
     * Nạp gói nếu chưa nạp. Trả về true khi gói đã có đủ (vừa nạp xong hoặc đã nạp từ trước).
     * Ghi dở dang (kho chưa sẵn sàng) thì trả về false và không để cờ, để lần mở app sau thử lại.
     */
    fun seedIfNeeded(store: MemoryStore, skills: PersonalSkills, flagFile: File, now: Long): Boolean {
        if (flagFile.exists()) return true
        // Kho chưa đọc được (khoá mã hoá chưa sẵn sàng) thì add() sẽ bị từ chối unavailable:
        // không để cờ, lần sau thử lại.
        if (!store.reloadIfNeeded()) return false
        val existing = store.items.value.map { it.content }.toHashSet()
        for (fact in FACTS) {
            if (fact.content in existing) continue
            val added = store.add(fact.kind, fact.content, fact.keywords, MemorySource("manual", atMs = now))
            if (added.isFailure) return false // ghi dở: không để cờ; lần sau mục đã có sẽ tự bỏ qua
        }
        for (s in skills(now)) {
            if (skills.get(s.name) == null) skills.put(s)
        }
        flagFile.parentFile?.mkdirs()
        flagFile.writeText("v1\n")
        return true
    }

    /** Điểm vào từ app: lấy store + kho kỹ năng dùng chung của [MemoryRuntime] và file cờ trong thư mục dữ liệu. */
    fun seedIfNeeded(runtime: MemoryRuntime, filesDir: File) {
        seedIfNeeded(runtime.store(), runtime.skills(), File(filesDir, FLAG_NAME), System.currentTimeMillis())
    }
}
