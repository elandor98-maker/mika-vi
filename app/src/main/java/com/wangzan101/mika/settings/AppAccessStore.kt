package com.wangzan101.mika.settings

import com.wangzan101.mika.core.access.AppAccessPolicy
import com.wangzan101.mika.core.access.AppAccessState
import com.wangzan101.mika.core.access.AppCategory
import com.wangzan101.mika.core.access.AppClassification
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** 应用权限与分类分开保存;没有记录的包名默认允许。 */
class AppAccessStore(private val file: File) : AppAccessPolicy {
    companion object {
        /**
         * Sàn chặn cứng của bản Phòng AI (chỉ thị trực tiếp của chủ máy): app ngân hàng, ví điện tử
         * và sàn tiền số thì tác nhân KHÔNG BAO GIỜ được chạm vào — kể cả khi bật chế độ toàn quyền,
         * kể cả khi người dùng lỡ tay gỡ chặn hay bấm "cho phép tất cả". Danh sách này được hợp nhất
         * vào [AppAccessState.blocked] ở mọi đường nạp/lưu (xem [withFloor]) nên không có lối tắt nào
         * gỡ được; muốn thêm app khác, chủ máy tự chặn thêm trong màn hình Quyền truy cập ứng dụng.
         * Tên gói đã đối chiếu với trang Google Play/APKMirror của từng hãng (11/2026).
         */
        val HARDCODED_BLOCKED: Set<String> = setOf(
            // Ngân hàng Việt Nam
            "com.VCB", // Vietcombank
            "com.vnpay.bidv", // BIDV
            "vn.com.techcombank.bb.app", // Techcombank
            "com.vietinbank.ipay", // VietinBank
            "com.mbmobile", // MB Bank
            "com.tpb.mb.gprsandroid", // TPBank
            "com.vnpay.Agribank3g", // Agribank
            "mobile.acb.com.vn", // ACB
            "com.vib.myvib2", // VIB
            "com.vnpay.hdbank", // HDBank
            "com.hdbank.dihdbank", // HDBank (Đi HDBank)
            // Ví điện tử Việt Nam
            "com.mservice.momotransfer", // MoMo
            "vn.com.vng.zalopay", // ZaloPay
            "com.bplus.vtpay", // Viettel Money
            "com.beeasy.toppay", // ShopeePay
            "vnpay.smartacccount", // VNPAY
            "vn.moca.pass", // Moca
            "vn.vimo", // Vimo
            // Ví/thanh toán quốc tế
            "com.paypal.android.p2pmobile", // PayPal
            "com.transferwise.android", // Wise
            "com.revolut.revolut", // Revolut
            // Sàn/ví tiền số
            "com.binance.dev", // Binance
            "com.coinbase.android", // Coinbase
            "com.okinc.okex.gp", // OKX
            "com.bybit.app", // Bybit
            "io.metamask", // MetaMask
            "com.wallet.crypto.trustapp", // Trust Wallet
        )

        /** Hợp nhất sàn chặn cứng vào trạng thái: các app trong [HARDCODED_BLOCKED] luôn bị chặn và không được phép chia sẻ màn hình. */
        fun withFloor(state: AppAccessState): AppAccessState =
            if (HARDCODED_BLOCKED.all { it in state.blocked } && state.shareAllowed.none { it in HARDCODED_BLOCKED }) state
            else state.copy(
                blocked = state.blocked + HARDCODED_BLOCKED,
                shareAllowed = state.shareAllowed - HARDCODED_BLOCKED,
            )
    }

    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    @Volatile var sensitiveUi: Boolean = false
        private set
    private var sensitiveCount = 0

    override fun current(): AppAccessState = mutable.value

    @Synchronized fun protectUi(): AutoCloseable {
        sensitiveCount++
        sensitiveUi = true
        var closed = false
        return AutoCloseable { synchronized(this) {
            if (!closed) { closed = true; sensitiveCount--; sensitiveUi = sensitiveCount > 0 }
        } }
    }

    @Synchronized fun setBlocked(pkg: String, blocked: Boolean) = update {
        it.copy(blocked = if (blocked) it.blocked + pkg else it.blocked - pkg, shareAllowed = it.shareAllowed - pkg)
    }

    @Synchronized fun allowAll() = update { it.copy(blocked = emptySet(), shareAllowed = emptySet()) }
    @Synchronized fun setShareAllowed(pkg: String, allowed: Boolean) = update {
        it.copy(shareAllowed = if (allowed) it.shareAllowed + pkg else it.shareAllowed - pkg)
    }

    @Synchronized fun classify(values: Map<String, AppClassification>) = update { s ->
        s.copy(classifications = s.classifications + values.filter { (pkg, c) -> c.manual || s.classifications[pkg]?.manual != true })
    }

    @Synchronized fun blockPackages(packages: Set<String>) = update {
        it.copy(blocked = it.blocked + packages, shareAllowed = it.shareAllowed - packages)
    }

    @Synchronized fun reset() = persist(withFloor(AppAccessState(revision = current().revision + 1)))

    private fun update(change: (AppAccessState) -> AppAccessState) {
        check(current().available) { "Application access settings must be reset before editing." }
        persist(withFloor(change(current())).copy(revision = current().revision + 1))
    }

    private fun persist(next: AppAccessState) {
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put("blocked", JsonArray(next.blocked.sorted().map(::JsonPrimitive)))
            put("shareAllowed", JsonArray(next.shareAllowed.sorted().map(::JsonPrimitive)))
            putJsonObject("classifications") {
                next.classifications.toSortedMap().forEach { (pkg, c) ->
                    putJsonObject(pkg) { put("category", c.category.name); put("reason", c.reason); put("manual", c.manual) }
                }
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        mutable.value = next
    }

    private fun load(): AppAccessState {
        if (!file.exists()) return withFloor(AppAccessState())
        return withFloor(runCatching {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            require(o["version"]?.jsonPrimitive?.int == 1)
            fun strings(key: String) = o.getValue(key).jsonArray.map { it.jsonPrimitive.content }.toSet()
            val classes = o.getValue("classifications").jsonObject.mapValues { (_, value) ->
                val c = value.jsonObject
                AppClassification(AppCategory.valueOf(c.getValue("category").jsonPrimitive.content),
                    c.getValue("reason").jsonPrimitive.content, c.getValue("manual").jsonPrimitive.boolean)
            }
            AppAccessState(strings("blocked"), strings("shareAllowed"), classes)
        }.getOrElse { AppAccessState(available = false) })
    }
}
