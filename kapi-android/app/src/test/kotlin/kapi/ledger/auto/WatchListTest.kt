package kapi.ledger.auto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认监听名单的约定测试（2026-10-04 电商默认移出，按友商定案）。
 *
 * 这份名单决定读屏"看哪里"，改错一个包名的代价是双向的：
 * 摘掉支付类 = 真支付不被发现（静默丢账）；放进电商类 = 购物浏览页
 * 凭空记账（用户实测撞过，2026-10-04）。两个方向都要有测试钉着。
 */
class WatchListTest {

    @Test
    fun `ecommerce apps are not watched by default`() {
        for (pkg in AutoBookStore.OPTIN_PACKAGES) {
            assertFalse("电商 $pkg 不该在默认名单里", pkg in AutoBookStore.DEFAULT_PACKAGES)
        }
    }

    @Test
    fun `core payment channels stay watched`() {
        // 这些是自动记账的命脉：电商支付兜底走它们的收银台和服务通知。
        for (pkg in setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.unionpay",
        )) {
            assertTrue("支付类 $pkg 必须留在默认名单", pkg in AutoBookStore.DEFAULT_PACKAGES)
        }
    }

    @Test
    fun `labels cover every package in both lists`() {
        for (pkg in AutoBookStore.DEFAULT_PACKAGES + AutoBookStore.OPTIN_PACKAGES) {
            assertTrue("包名 $pkg 缺展示名", AutoBookStore.PACKAGE_LABELS.containsKey(pkg))
        }
    }

    @Test
    fun `optin list does not overlap default`() {
        assertTrue(
            "两个名单有交集：${AutoBookStore.DEFAULT_PACKAGES intersect AutoBookStore.OPTIN_PACKAGES}",
            (AutoBookStore.DEFAULT_PACKAGES intersect AutoBookStore.OPTIN_PACKAGES).isEmpty(),
        )
    }
}
