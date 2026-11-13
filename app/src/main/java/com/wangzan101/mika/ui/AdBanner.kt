package com.wangzan101.mika.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * Mã đơn vị quảng cáo THỬ NGHIỆM công khai của Google (banner): chỉ hiện quảng cáo mẫu,
 * không tính tiền thật. Khi phát hành bản kiếm tiền, thay bằng mã đơn vị của tài khoản AdMob thật.
 */
private const val TEST_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"

/**
 * Một banner AdMob neo cố định (anchored adaptive) — trên trang chat nó nằm ngay dưới thanh nhập,
 * trong cùng cột được đo chiều cao nên danh sách tin nhắn tự đệm đáy, không bị che.
 * Chiều cao giữ chỗ trước theo kích thước banner nên thanh nhập không nhảy khi quảng cáo nạp xong.
 */
@Composable
fun AdBannerBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adSize = remember(context) {
        val metrics = context.resources.displayMetrics
        val widthDp = (metrics.widthPixels / metrics.density).toInt().coerceAtLeast(1)
        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(adSize.height.dp),
        factory = { ctx ->
            AdView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setAdSize(adSize)
                adUnitId = TEST_BANNER_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() },
    )
}
