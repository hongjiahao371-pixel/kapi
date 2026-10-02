package kapi.ledger.auto

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kapi.core.llm.Money
import kapi.ledger.container
import kapi.ledger.data.CategoryEntity
import kapi.ledger.data.AutoDraftEntity
import kapi.ledger.sync.LocalEditWriter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 识别到支付后的**悬浮确认窗**：从屏幕底部弹出，占 2/3 屏高，类别已归好，
 * 用户三选一——确认入账 / 不记了 / 忽略（留待确认）。
 *
 * ## 与草稿的关系（2026-10-02）
 *
 * 悬浮窗**不替代**草稿：[AutoCapturePipeline] 照旧建 [kapi.ledger.data.AutoDraftEntity]
 * （去重/合并/兜底语义全部不变），本窗只是把「待确认」提前推到用户眼前——
 * - 确认 → 走与 DraftsScreen.confirmNow 完全相同的落库路径，删草稿
 * - 不记了 → 删草稿
 * - 忽略 / ✕ / 90 秒超时 → 什么都不动，草稿留在待确认页兜底
 *
 * 因此悬浮窗挂掉、没授权、被用户随手划走，都**不会丢数据**。
 *
 * ## 权限
 *
 * TYPE_APPLICATION_OVERLAY 需要「显示在其它应用上层」特殊权限——清单里已声明
 * （悬浮球时代留下），但授权必须用户在系统设置里手动给。没授权时 [show] 直接
 * 返回，草稿走原有兜底；设置页有开关和授权入口。
 *
 * ## 为什么用裸 View 不用 Compose
 *
 * 服务窗口没有 Compose 的生命周期宿主，挂 ComposeView 要额外接
 * ViewCompositionStrategy 和 LifecycleOwner——为一个两按钮的确认条不值得。
 * 裸 View 的另一个好处：样式与主题解耦，深浅色都用同一套深色玻璃参数
 * （悬浮窗永远浮在别人的 App 上，背景不可控，深色卡对比度最稳）。
 */
class BillOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var root: View? = null

    /** 当前窗上那笔草稿。同一时刻只显示一笔——新草稿来了直接顶掉旧的（旧稿留待确认）。 */
    private var currentDraftId: String? = null

    /** 用户在窗上改过的类目（null = 没改，确认时用 AutoCategory 的解析结果）。 */
    private var pickedCategory: CategoryEntity? = null

    /** 用户在窗上改过的备注（null = 没改）。 */
    private var pickedNote: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        removeWindow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val draftId = intent?.getStringExtra(EXTRA_DRAFT_ID)
        if (draftId != null) show(draftId)
        return START_NOT_STICKY
    }

    /** 入口：管线在 Drafted 之后调用。无悬浮窗权限时静默跳过（草稿走兜底）。 */
    private fun show(draftId: String) {
        if (!Settings.canDrawOverlays(this)) return
        currentDraftId = draftId
        pickedCategory = null
        pickedNote = null
        scope.launch {
            val draft = withContext(Dispatchers.IO) {
                container.db.autoDraftDao().byId(draftId)
            } ?: return@launch // 草稿已被合并/清理，无事可做
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    AutoCategory.resolve(
                        db = container.db,
                        ledgerId = draft.ledgerId,
                        merchant = draft.merchant,
                        rawText = draft.rawText,
                        direction = draft.direction,
                    )
                }.getOrNull()
            }
            val categories = withContext(Dispatchers.IO) {
                container.db.categoryDao()
                    .observeForLedger(draft.ledgerId)
                    .first()
                    .filter { !it.deleted && it.kind == draft.direction }
            }
            val account = withContext(Dispatchers.IO) {
                draft.accountId?.let { container.db.accountDao().byId(it) }
            }
            showWindow(draft, resolved, categories, account?.name)
        }
    }

    // ------------------------------------------------------------ 窗口

    private fun showWindow(
        draft: AutoDraftEntity,
        resolvedCategoryId: String?,
        categories: List<CategoryEntity>,
        accountName: String?,
    ) {
        removeWindow()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenH = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val card = buildCard(draft, resolvedCategoryId, categories, accountName, density)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (screenH * 2) / 3,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.BOTTOM
        card.translationY = params.height.toFloat() // 初始沉在屏外，入场时滑上来
        root = card
        wm.addView(card, params)
        windowManager = wm
        card.animate().translationY(0f).setDuration(260).start()
        // 90 秒无操作自动收起：草稿留在待确认，不丢数据
        handler.postDelayed({ removeWindow() }, AUTO_HIDE_MS)
    }

    private fun removeWindow() {
        root?.let { windowManager?.removeView(it) }
        root = null
    }

    // ------------------------------------------------------------ 视图

    private fun buildCard(
        draft: AutoDraftEntity,
        resolvedCategoryId: String?,
        categories: List<CategoryEntity>,
        accountName: String?,
        density: Float,
    ): View {
        fun dp(v: Int): Int = (v * density).toInt()
        val ctx = this

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(18), dp(20), dp(18))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2151515"))
        bg.cornerRadius = dp(24).toFloat()
        card.background = bg

        fun title(text: String, size: Int, color: String, bold: Boolean = false): TextView =
            TextView(ctx).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
                setTextColor(Color.parseColor(color))
                this.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }

        // ---- 标题行
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(
            title("识别到一笔支付", 16, "#E6FFFFFF").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val close = title("✕", 16, "#99FFFFFF")
        close.setPadding(dp(16), 0, dp(4), 0)
        close.setOnClickListener { removeWindow() }
        head.addView(close)
        card.addView(head)

        // ---- 金额 + 方向
        val amountText = (if (draft.direction == "INCOME") "+" else "-") +
            Money.toYuanDisplay(draft.amountMinor)
        val amountColor = if (draft.direction == "INCOME") "#4CD98A" else "#FF7A65"
        card.addView(
            title(amountText, 44, amountColor, bold = true).apply {
                setPadding(0, dp(10), 0, 0)
            },
        )

        // ---- 字段行
        fun row(label: String, value: String) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(title(label, 13, "#8AFFFFFF").apply { layoutParams = LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT) })
            r.addView(title(value, 13, "#E6FFFFFF"))
            card.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) })
        }
        row("商户", draft.merchant ?: "（未识别）")
        row("时间", SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(draft.occurredAt)))
        row("账户", accountName ?: "默认账户")
        row("来源", "自动读屏 · 待你确认")

        // ---- 类目：已归好的结果显示 + 点芯片可改
        val resolved = categories.firstOrNull { it.id == resolvedCategoryId }
        card.addView(
            title("类目（点下方可修改）", 13, "#8AFFFFFF").apply { setPadding(0, dp(14), 0, dp(2)) },
        )
        val categoryValue = title(
            resolved?.let { it.parentTextOrNull(categories) ?: it.name } ?: "未分类",
            16,
            "#7FE6D9",
            bold = true,
        )
        card.addView(categoryValue)

        var picked = resolved
        val chipRow = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val chipWrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        chipRow.addView(chipWrap)
        val minorRow = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val minorWrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        fun refreshChips() {
            chipWrap.removeAllViews()
            minorWrap.removeAllViews()
            val majors = categories.filter { it.parentId == null }
            val minorsOf: (String) -> List<CategoryEntity> = { pid ->
                categories.filter { it.parentId == pid }
            }
            for (cat in majors) {
                val selected = picked?.id == cat.id || (picked == null && resolved?.topParentId(categories) == cat.id)
                val chip = TextView(ctx).apply {
                    text = cat.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    val r = GradientDrawable()
                    r.cornerRadius = dp(16).toFloat()
                    r.setColor(Color.parseColor(if (selected) "#337FE6D9" else "#22FFFFFF"))
                    background = r
                    setTextColor(Color.parseColor(if (selected) "#7FE6D9" else "#CCFFFFFF"))
                    setOnClickListener {
                        picked = cat
                        pickedCategory = cat
                        categoryValue.text = cat.name
                        refreshChips()
                    }
                }
                chipWrap.addView(chip)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = dp(8)
                chip.layoutParams = lp
            }
            // 细分类行：选中大类且它有子类时出现
            picked?.let { p ->
                val kids = minorsOf(p.id)
                if (kids.isNotEmpty()) {
                    minorRow.visibility = View.VISIBLE
                    for (kid in kids) {
                        val selected = picked?.id == kid.id
                        val chip = TextView(ctx).apply {
                            text = kid.name
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(dp(12), dp(4), dp(12), dp(4))
                            val r = GradientDrawable()
                            r.cornerRadius = dp(14).toFloat()
                            r.setColor(Color.parseColor(if (selected) "#337FE6D9" else "#1AFFFFFF"))
                            background = r
                            setTextColor(Color.parseColor(if (selected) "#7FE6D9" else "#AAFFFFFF"))
                            setOnClickListener {
                                picked = kid
                                pickedCategory = kid
                                categoryValue.text = "${p.name}/${kid.name}"
                                refreshChips()
                            }
                        }
                        minorWrap.addView(chip)
                        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        lp.marginEnd = dp(6)
                        chip.layoutParams = lp
                    }
                }
            }
        }
        refreshChips()
        card.addView(chipRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) })
        card.addView(minorRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) })

        // ---- 备注（可改）
        card.addView(
            title("备注", 13, "#8AFFFFFF").apply { setPadding(0, dp(12), 0, dp(2)) },
        )
        val noteEdit = EditText(ctx).apply {
            hint = "可留空"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundResource(android.R.color.transparent)
            setText(pickedNote ?: draft.note.orEmpty())
            maxLines = 2
        }
        noteEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                pickedNote = e?.toString()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        card.addView(noteEdit)

        // ---- 按钮行：不记了 / 稍后 / 确认入账
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, textColor: String, bgColor: String, weight: Float, onClick: () -> Unit) {
            val b = TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(textColor))
                val r = GradientDrawable()
                r.cornerRadius = dp(14).toFloat()
                r.setColor(Color.parseColor(bgColor))
                background = r
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(0, dp(46), weight)
            lp.marginEnd = dp(10)
            b.layoutParams = lp
            buttons.addView(b)
        }
        button("不记了", "#FF8A80", "#22FFFFFF", 1f) { onDiscard() }
        button("稍后", "#CCFFFFFF", "#22FFFFFF", 1f) { removeWindow() }
        // 主按钮亮底深字，视觉上压过两个次级按钮
        button("确认入账", "#0F1515", "#7FE6D9", 1.2f) { onConfirm(draft) }
        card.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).also { it.topMargin = dp(16) })
        return card
    }

    // ------------------------------------------------------------ 动作

    /** 不记了：删草稿，什么都不记。 */
    private fun onDiscard() {
        val id = currentDraftId ?: return
        scope.launch {
            withContext(Dispatchers.IO) { container.db.autoDraftDao().delete(id) }
            removeWindow()
        }
    }

    /** 确认入账：落库路径与 DraftsScreen.confirmNow 相同，类目/备注以窗上为准。 */
    private fun onConfirm(draft: AutoDraftEntity) {
        val pickedCategoryFinal = pickedCategory
        val noteFinal = pickedNote ?: draft.note
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val fresh = container.db.autoDraftDao().byId(draft.id)
                        ?: throw IllegalStateException("该笔已在别处处理")
                    val categoryId = pickedCategoryFinal?.id
                        ?: AutoCategory.resolve(
                            db = container.db,
                            ledgerId = fresh.ledgerId,
                            merchant = fresh.merchant,
                            rawText = fresh.rawText,
                            direction = fresh.direction,
                        )
                    container.editor.createTransaction(
                        amountMinor = fresh.amountMinor,
                        direction = fresh.direction,
                        accountId = fresh.accountId ?: "A1",
                        occurredAt = fresh.occurredAt,
                        ledgerId = fresh.ledgerId,
                        deviceId = container.tokenStore.deviceId,
                        note = noteFinal,
                        merchant = fresh.merchant,
                        categoryId = categoryId,
                        source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
                        confidence = fresh.confidence,
                    )
                    container.db.autoDraftDao().delete(fresh.id)
                }
                removeWindow()
            }.onFailure {
                android.util.Log.w("BillOverlay", "确认入账失败：${it.javaClass.name}: ${it.message}", it)
                removeWindow() // 草稿未删，留在待确认兜底
            }
        }
    }

    private fun CategoryEntity.parentTextOrNull(categories: List<CategoryEntity>): String? =
        parentId?.let { pid -> categories.firstOrNull { it.id == pid }?.let { "${it.name}/$name" } } ?: name

    private fun CategoryEntity.topParentId(categories: List<CategoryEntity>): String =
        parentId ?: id

    companion object {
        private const val EXTRA_DRAFT_ID = "draft_id"
        private const val AUTO_HIDE_MS = 90_000L

        /** 管线 Drafted 之后调用。无悬浮窗权限时静默跳过。 */
        fun show(context: Context, draftId: String) {
            val intent = Intent(context, BillOverlayService::class.java)
                .putExtra(EXTRA_DRAFT_ID, draftId)
            context.startService(intent)
        }
    }
}
