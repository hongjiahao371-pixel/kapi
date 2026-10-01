package kapi.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMergeTest {

    private val device = "phone"
    private fun clock() = LamportClock(0)

    private fun row(id: String = "t1") = SyncedRow(entity = "txn", entityId = id)

    private fun snap(
        id: String = "t1",
        values: Map<String, String> = emptyMap(),
        fv: FieldVersions = emptyMap(),
        delFv: FieldVersion = FieldVersion(),
    ) = Snapshot(
        entity = "txn", entityId = id, values = values, fv = fv, delFv = delFv, lamport = 0,
    )

    // -----------------------------------------------------------------------
    // 核心不变量
    // -----------------------------------------------------------------------

    /**
     * 本地已改但还没推上去的字段，远端快照不许覆盖。
     *
     * 这是整个客户端最容易写错、后果最严重的一条：如果直接用远端快照盖本地，
     * 用户刚记的账会在推上去之前凭空消失，而且用户什么都察觉不到。
     * 远端快照反映的是服务端**在收到我们这次推送之前**的状态。
     */
    @Test
    fun `remote snapshot must not clobber an unpushed local edit`() {
        val clk = clock()
        val r = LocalMerge.applyLocalEdit(row(), "amount_minor", "2350", clk, device, "c1")
        assertTrue(r.hasPending)

        val remote = snap(values = mapOf("amount_minor" to "9999"), fv = mapOf("amount_minor" to FieldVersion(99, "tablet")))

        val after = LocalMerge.applyRemote(r, remote)

        assertEquals("本地未推送的编辑必须保住", "2350", after.values["amount_minor"])
        assertTrue("仍然处于待推送", after.hasPending)
    }

    /** 但服务端知道的基线要更新到远端戳，否则推送时比较的是错的基线。 */
    @Test
    fun `server baseline advances even when the value is withheld`() {
        val clk = clock()
        val r = LocalMerge.applyLocalEdit(row(), "amount_minor", "2350", clk, device, "c1")
        val remote = snap(values = mapOf("amount_minor" to "9999"), fv = mapOf("amount_minor" to FieldVersion(99, "tablet")))

        val after = LocalMerge.applyRemote(r, remote)

        assertEquals(FieldVersion(99, "tablet"), after.serverFv["amount_minor"])
    }

    /** 没有 pending 的字段，远端快照照常应用。 */
    @Test
    fun `remote snapshot applies when nothing is pending`() {
        val r = row()
        val remote = snap(
            values = mapOf("amount_minor" to "9999", "note" to "午餐"),
            fv = mapOf("amount_minor" to FieldVersion(99, "tablet"), "note" to FieldVersion(99, "tablet")),
        )
        val after = LocalMerge.applyRemote(r, remote)
        assertEquals("9999", after.values["amount_minor"])
        assertEquals("午餐", after.values["note"])
        assertFalse(after.hasPending)
    }

    /** 混合场景：本地改了金额、没改备注，远端快照同时带两者 —— 各取所需。 */
    @Test
    fun `field level apply takes each field from the right side`() {
        val clk = clock()
        val r = LocalMerge.applyLocalEdit(row(), "amount_minor", "2350", clk, device, "c1")
        val remote = snap(
            values = mapOf("amount_minor" to "9999", "note" to "同事代付"),
            fv = mapOf("amount_minor" to FieldVersion(99, "tablet"), "note" to FieldVersion(99, "tablet")),
        )
        val after = LocalMerge.applyRemote(r, remote)
        assertEquals("2350", after.values["amount_minor"])
        assertEquals("同事代付", after.values["note"])
    }

    // -----------------------------------------------------------------------
    // 推送生命周期
    // -----------------------------------------------------------------------

    @Test
    fun `push success clears pending and promotes stamps to server known`() {
        val clk = clock()
        val r = LocalMerge.applyLocalEdit(row(), "amount_minor", "2350", clk, device, "c1")
        val out = LocalMerge.buildOutboxChange(r, device)!!

        assertEquals("2350", out.data["amount_minor"])
        assertEquals(LocalMerge.OP_UPSERT, out.op)

        val after = LocalMerge.applyPushSuccess(r, out)
        assertFalse(after.hasPending)
        assertEquals(clk.current, after.serverFv["amount_minor"]!!.lamport)
    }

    /**
     * 推送失败重试必须用同一个 change_id，否则服务端幂等去重失效，同一笔改动会被
     * 推两遍。change_id 由「行 + 本批最高 lamport + 设备」构成。
     */
    @Test
    fun `change id is stable across retries of the same batch`() {
        val clk = clock()
        val r = LocalMerge.applyLocalEdit(row(), "amount_minor", "2350", clk, device, "c1")
        val a = LocalMerge.buildOutboxChange(r, device)!!
        val b = LocalMerge.buildOutboxChange(r, device)!!
        assertEquals(a.changeId, b.changeId)
    }

    @Test
    fun `a second distinct edit gets a different change id`() {
        val clk = clock()
        val r1 = LocalMerge.applyLocalEdit(row(), "amount_minor", "2350", clk, device, "c1")
        val first = LocalMerge.buildOutboxChange(r1, device)!!

        val sent = LocalMerge.applyPushSuccess(r1, first)
        val r2 = LocalMerge.applyLocalEdit(sent, "amount_minor", "2400", clk, device, "c2")
        val second = LocalMerge.buildOutboxChange(r2, device)!!

        assertFalse("两次独立编辑不能共用 change_id", first.changeId == second.changeId)
    }

    /** 多字段一次推送合成一条变更，服务端逐字段合并。 */
    @Test
    fun `multi field edit becomes one change with per field stamps`() {
        val clk = clock()
        var r = LocalMerge.applyLocalEdit(row(), "amount_minor", "2350", clk, device, "c1")
        r = LocalMerge.applyLocalEdit(r, "note", "午餐", clk, device, "c1")
        val out = LocalMerge.buildOutboxChange(r, device)!!

        assertEquals(2, out.data.size)
        assertEquals(2, out.fv.size)
        assertNotNull(out.fv["amount_minor"])
        assertNotNull(out.fv["note"])
    }

    /** 无 pending 时不产生 outbox 条目。 */
    @Test
    fun `no pending means no outbox entry`() {
        assertNull(LocalMerge.buildOutboxChange(row(), device))
    }

    // -----------------------------------------------------------------------
    // 拒绝
    // -----------------------------------------------------------------------

    /**
     * 被服务端拒绝的字段丢弃本地版本，权威值由下一次拉取带回。
     *
     * 刻意**不在这里**替换成「服务端认为的正确值」——我们并不知道那是什么，
     * 编一个中间状态出来只会掩盖问题。在拒绝和下一次拉取之间，本地短暂显示
     * 用户自己输的值，用户看到的是"我做了什么"，而不是系统在偷偷改。
     */
    @Test
    fun `rejected fields are dropped so the next pull can correct them`() {
        val clk = clock()
        val r = LocalMerge.applyLocalEdit(row(), "amount_minor", "999", clk, device, "c1")
        val out = LocalMerge.buildOutboxChange(r, device)!!

        val after = LocalMerge.applyPushRejection(r, out, listOf("amount_minor"))

        assertFalse(after.hasPending)
    }

    /** 只被拒一个字段时，另一个字段的 pending 必须保留。 */
    @Test
    fun `partial rejection keeps the other fields pending`() {
        val clk = clock()
        var r = LocalMerge.applyLocalEdit(row(), "amount_minor", "999", clk, device, "c1")
        r = LocalMerge.applyLocalEdit(r, "note", "午餐", clk, device, "c1")
        val out = LocalMerge.buildOutboxChange(r, device)!!

        val after = LocalMerge.applyPushRejection(r, out, listOf("amount_minor"))

        assertTrue(after.pending.containsKey("note"))
        assertFalse(after.pending.containsKey("amount_minor"))
    }

    // -----------------------------------------------------------------------
    // 删除
    // -----------------------------------------------------------------------

    /** 本地删除立刻对用户隐藏，但在推送成功前不假装服务端已经删了。 */
    @Test
    fun `local delete hides immediately and stays pending until pushed`() {
        val clk = clock()
        val r = LocalMerge.applyLocalEdit(row(), DELETED_FIELD, "1", clk, device, "c1")

        assertTrue("删除后应立即不可见", !r.isVisible())
        val out = LocalMerge.buildOutboxChange(r, device)!!
        assertEquals(LocalMerge.OP_DELETE, out.op)
        assertTrue("纯删除不应带数据字段", out.data.isEmpty())

        val after = LocalMerge.applyPushSuccess(r, out)
        assertFalse(after.hasPending)
    }

    /**
     * 本地还挂着未推送的删除时，服务端说「这行还活着」不能让它复活。
     *
     * 否则会出现：用户删了一笔 → 同步没赶上 → 另一台设备拉了一份旧的回来 →
     * 刚删掉的账又冒出来。
     */
    @Test
    fun `a pending local delete is not undone by a remote live row`() {
        val clk = clock()
        val r = LocalMerge.applyLocalEdit(row(), DELETED_FIELD, "1", clk, device, "c1")
        val remote = snap(
            values = mapOf("amount_minor" to "2350"),
            fv = mapOf("amount_minor" to FieldVersion(5, "tablet")),
        )
        val after = LocalMerge.applyRemote(r, remote)
        assertTrue("未推送的删除不能被远端复活", !after.isVisible())
    }

    /** 服务端说已删除时，本地立刻跟着隐藏。 */
    @Test
    fun `remote delete hides the row`() {
        val remote = snap(
            values = mapOf("amount_minor" to "2350"),
            fv = mapOf("amount_minor" to FieldVersion(5, "tablet")),
            delFv = FieldVersion(50, "tablet"),
        )
        val after = LocalMerge.applyRemote(row(), remote)
        assertTrue(after.deleted)
        assertFalse(after.isVisible())
    }

    /** 服务端墓碑戳被内容戳压过时（行已被复活），本地也必须按同一规则判定。 */
    @Test
    fun `tombstone loses to newer content, same as the server rule`() {
        val remote = snap(
            values = mapOf("amount_minor" to "2350"),
            fv = mapOf("amount_minor" to FieldVersion(80, "tablet")),
            delFv = FieldVersion(50, "phone"),
        )
        assertFalse("墓碑戳 50 不该压过内容戳 80", remote.isDeleted())
    }

    // -----------------------------------------------------------------------
    // Lamport
    // -----------------------------------------------------------------------

    @Test
    fun `clock is monotonic and absorbs remote stamps`() {
        val c = clock()
        assertEquals(1L, c.tick())
        assertEquals(2L, c.tick())
        c.observe(100)
        assertEquals(101L, c.tick())
    }

    @Test
    fun `observing an older stamp does not move the clock backwards`() {
        val c = clock()
        c.observe(100)
        c.observe(5)
        assertEquals(101L, c.tick())
    }

    @Test
    fun `device id breaks ties deterministically`() {
        assertTrue(FieldVersion(10, "tablet") > FieldVersion(10, "phone"))
        assertTrue(FieldVersion(11, "phone") > FieldVersion(10, "tablet"))
    }
}
