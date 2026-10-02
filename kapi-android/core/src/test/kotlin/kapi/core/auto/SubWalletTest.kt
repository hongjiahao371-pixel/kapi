package kapi.core.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 子钱包目录：匹配顺序、封闭边界、id 一致性。 */
class SubWalletTest {

    /** 「零钱通」包含「零钱」——顺序错了微信的零钱通会被记到零钱头上。 */
    @Test
    fun `longer wallet words win over their substrings`() {
        assertEquals("零钱通", SubWallet.match("零钱通")?.name)
        assertEquals("零钱", SubWallet.match("零钱")?.name)
        assertEquals("账户余额", SubWallet.match("账户余额")?.name)
    }

    /** 真机原文的带修饰写法（账户字段值）也要能匹配。 */
    @Test
    fun `decorated account names still match`() {
        assertEquals("零钱", SubWallet.match("微信零钱")?.name)
        assertEquals("花呗", SubWallet.match("花呗额度")?.name)
        assertEquals("余额宝", SubWallet.match("余额宝-天弘基金")?.name)
    }

    /** 封闭边界：银行卡、人名、空值不映射——宁落日志不乱记。 */
    @Test
    fun `banks and people are not wallets`() {
        assertNull(SubWallet.match("招商银行储蓄卡(0416)"))
        assertNull(SubWallet.match("交通银行信用卡(5894)"))
        assertNull(SubWallet.match("啦啦啦(朱娜娜)"))
        assertNull(SubWallet.match(""))
        assertNull(SubWallet.match(null))
    }

    /** 账户类型：货基是 INVESTMENT，花呗是 CREDIT，余额是 DEBIT。 */
    @Test
    fun `kinds follow what the wallet actually is`() {
        assertEquals("INVESTMENT", SubWallet.match("余额宝")?.kind)
        assertEquals("INVESTMENT", SubWallet.match("零钱通")?.kind)
        assertEquals("CREDIT", SubWallet.match("花呗")?.kind)
        assertEquals("DEBIT", SubWallet.match("账户余额")?.kind)
    }

    /** id 往返一致；id 集合与种子约定（A5…A10）不漂移。 */
    @Test
    fun `id round trip is stable`() {
        for (w in listOf("余额宝", "零钱通", "零钱", "账户余额", "花呗", "存钱罐")) {
            val wallet = SubWallet.match(w)!!
            assertEquals(w, SubWallet.byId(wallet.accountId)?.name)
        }
        val ids = SubWallet.match("余额宝")!!.let { listOf(it.accountId) }
        assertEquals(listOf("A5"), ids)
    }
}
