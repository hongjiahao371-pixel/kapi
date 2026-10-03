package kapi.ledger.auto

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import kapi.core.auto.AutoBook
import kapi.core.auto.PaymentId
import kapi.core.auto.NoteComposer
import kapi.core.llm.LocalTextParser
import kapi.core.llm.Money
import kapi.ledger.container
import kapi.ledger.data.CategoryEntity
import kapi.ledger.data.AccountEntity
import kapi.ledger.data.AutoDraftEntity
import kapi.ledger.data.LedgerRepository
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

    private enum class Mode { CONFIRM, RECORDED }

    private var mode = Mode.CONFIRM

    /** 当前窗的原文指纹与弹出时刻：同一页的事件流会在几秒内反复触发，去重弹窗自己。 */
    private var lastShownKey: String? = null
    private var lastShownAtMs: Long = 0

    /** 用户在窗上改过的类目（null = 没改，确认时用 AutoCategory 的解析结果）。 */
    private var pickedCategory: CategoryEntity? = null

    /** 用户在窗上改过的备注（null = 没改）。 */
    private var pickedNote: String? = null

    /** 用户在窗上改过的金额（展示串，确认时经 Money.parse 回 minor；null = 没改）。 */
    private var pickedAmount: String? = null

    /** 用户在窗上选过的账户 id（null = 没改，沿用草稿/推断账户）。 */
    private var pickedAccountId: String? = null

    private var allAccounts: List<AccountEntity> = emptyList()

    /**
     * 已记录模式的合成展示实体：没有草稿行，金额/方向/商户/时间全部来自
     * 账单原文解析，「再记一次」按它入账。
     */
    private var recordedDraft: AutoDraftEntity? = null
    private var recordedResolvedCategoryId: String? = null
    private var recordedCategories: List<CategoryEntity> = emptyList()
    private var recordedPkg: String? = null
    private var recordedRawText: String? = null
    private var recordedOccurredAt: Long = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        removeWindow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_RECORDED -> {
                val text = intent.getStringExtra(EXTRA_RAW_TEXT) ?: return START_NOT_STICKY
                val at = intent.getLongExtra(EXTRA_OCCURRED_AT, System.currentTimeMillis())
                val pkg = intent.getStringExtra(EXTRA_PKG)
                showRecorded(text, at, pkg)
            }
            MODE_AUTOPOST -> {
                val draftId = intent.getStringExtra(EXTRA_DRAFT_ID) ?: return START_NOT_STICKY
                autoPostAndShow(draftId)
            }
            else -> {
                val draftId = intent?.getStringExtra(EXTRA_DRAFT_ID)
                if (draftId != null) showConfirm(draftId)
            }
        }
        return START_NOT_STICKY
    }

    /** 入口一：管线在 Drafted 之后调用（没记录过的账单）。无权限时静默跳过。 */
    private fun showConfirm(draftId: String) {
        if (!Settings.canDrawOverlays(this)) return
        mode = Mode.CONFIRM
        currentDraftId = draftId
        pickedCategory = null
        pickedNote = null
        pickedAmount = null
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

    /**
     * 入口二：去重命中（这笔之前记过）之后调用。没有草稿行，信息从账单
     * 原文现解析；解析不出金额（非支付页/加载残屏）就静默跳过。
     */
    private fun showRecorded(text: String, at: Long, pkg: String?) {
        if (!Settings.canDrawOverlays(this)) return
        mode = Mode.RECORDED
        currentDraftId = null
        pickedCategory = null
        pickedNote = null
        pickedAmount = null
        recordedRawText = text
        recordedOccurredAt = at
        recordedPkg = pkg
        scope.launch {
            val result = LocalTextParser.parse(text, at) ?: return@launch
            recordedDraft = AutoDraftEntity(
                id = "",
                ledgerId = LedgerRepository.DEFAULT_LEDGER_ID,
                amountMinor = result.amountsMinor.firstOrNull() ?: 0L,
                direction = result.direction.name,
                occurredAt = result.occurredAt,
                merchant = result.merchant,
                note = NoteComposer.compose(text),
                accountId = null,
                source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
                confidence = result.confidence,
                fingerprint = "",
                createdAt = System.currentTimeMillis(),
                originPackage = pkg,
                rawText = text.take(2000),
                sources = "A11Y",
                evidenceCount = 1,
            )
            recordedResolvedCategoryId = withContext(Dispatchers.IO) {
                runCatching {
                    AutoCategory.resolve(
                        db = container.db,
                        ledgerId = LedgerRepository.DEFAULT_LEDGER_ID,
                        merchant = result.merchant,
                        rawText = text,
                        direction = result.direction.name,
                    )
                }.getOrNull()
            }
            recordedCategories = withContext(Dispatchers.IO) {
                container.db.categoryDao()
                    .observeForLedger(LedgerRepository.DEFAULT_LEDGER_ID)
                    .first()
                    .filter { !it.deleted && it.kind == result.direction.name }
            }
            allAccounts = withContext(Dispatchers.IO) {
                container.db.accountDao()
                    .observeForLedger(LedgerRepository.DEFAULT_LEDGER_ID)
                    .first()
                    .filter { !it.deleted }
            }
            val rd = recordedDraft ?: return@launch
            showWindow(rd, recordedResolvedCategoryId, recordedCategories, null)
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

        // 高度 WRAP_CONTENT：窗口随内容伸缩（内部 ScrollView 自己封顶），
        // 重回「少内容少占屏、多内容不出屏」。
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.BOTTOM
        // 刻意**不做**背景模糊：悬浮窗下面是用户正在看的账单详情页，
        // 模糊它等于把用户要看的内容糊掉（2026-10-02 用户反馈）。
        // 白卡的「玻璃感」由高不透明度+描边+投影表达，不吃背景。
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
        val recorded = mode == Mode.RECORDED

        val card = LinearLayout(ctx)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(20), dp(8), dp(20), dp(20))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F7FFFFFF"))
        bg.cornerRadius = dp(28).toFloat()
        bg.setStroke(Math.max(1, dp(1)), Color.parseColor("#14000000"))
        card.background = bg
        card.elevation = dp(10).toFloat()

        // 抓握条 40x4dp
        val grab = View(ctx)
        grab.layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
            bottomMargin = dp(14)
        }
        val grabBg = GradientDrawable()
        grabBg.cornerRadius = dp(2).toFloat()
        grabBg.setColor(Color.parseColor("#33000000"))
        grab.background = grabBg
        card.addView(grab)

        fun title(text: String, size: Int, color: String, bold: Boolean = false): TextView =
            TextView(ctx).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
                setTextColor(Color.parseColor(color))
                this.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }

        // ---- 标题行：15sp 粗体居中（左右等权占位），✕ 靠右
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(ctx), LinearLayout.LayoutParams(0, dp(24), 1f))
        head.addView(
            title(if (recorded) "当前账单已记录" else "识别到一笔支付", 15, "#171E2E", bold = true),
        )
        head.addView(
            title("✕", 16, "#9AA3B2").apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, 0, 0)
                setOnClickListener { removeWindow() }
                layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
            },
        )
        card.addView(head)

        // ---- 已记录模式：浅黄提示条，把两种语义在视觉上彻底分开
        if (recorded) {
            val tip = title("这笔已在账本里，可选择是否再记一次", 12, "#8A6D1F").apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val tb = GradientDrawable()
            tb.cornerRadius = dp(10).toFloat()
            tb.setColor(Color.parseColor("#FFF8E1"))
            tip.background = tb
            card.addView(
                tip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).also { it.topMargin = dp(12) },
            )
        }

        // ---- 可滚动内容区 ----
        // 卡片内容（提示条+金额+字段+备注）可能超过窗口高度；此前整个
        // 卡片硬塞进固定 2/3 屏的窗口，超出部分被屏幕底边裁掉——按钮
        // 看不见也点不着（2026-10-03 真机反馈「悬浮窗显示不完整」）。
        // 现在：中间内容放进 ScrollView（上限 58% 屏高，内容少时自动
        // 收缩），按钮区留在滚动区外钉底，永远完整可见可点。
        val bodyScroll = object : android.widget.ScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (resources.displayMetrics.heightPixels * 0.58f).toInt()
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST),
                )
            }
        }
        bodyScroll.isVerticalScrollBarEnabled = false
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        bodyScroll.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            bodyScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // ---- 金额（可改）：36sp 粗体居中，上下各 20dp
        val direction = draft.direction
        val directionColor = if (direction == "INCOME") "#12A594" else "#E5484D"
        val amountRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val amountRowLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.topMargin = dp(20); it.bottomMargin = dp(20) }
        amountRow.addView(title(if (direction == "INCOME") "+" else "-", 36, directionColor, bold = true))
        val amountEdit = EditText(ctx).apply {
            setText(Money.toYuanDisplay(draft.amountMinor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(directionColor))
            setBackgroundResource(android.R.color.transparent)
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(4), dp(4), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        pickedAmount = Money.toYuanDisplay(draft.amountMinor)
        amountEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) {
                pickedAmount = e?.toString()
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        amountRow.addView(amountEdit)
        body.addView(amountRow, amountRowLp)

        // ---- 内嵌字段卡 #F7F8FA 圆角 12dp
        val fieldCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        val fieldBg = GradientDrawable()
        fieldBg.setColor(Color.parseColor("#F7F8FA"))
        fieldBg.cornerRadius = dp(12).toFloat()
        fieldCard.background = fieldBg
        body.addView(
            fieldCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        fun divider() {
            val line = View(ctx)
            line.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1)),
            ).also { it.marginStart = dp(2); it.marginEnd = dp(2) }
            line.setBackgroundColor(Color.parseColor("#14000000"))
            fieldCard.addView(line)
        }

        fun rowLp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        )

        fun label(text: String): TextView = title(text, 14, "#666F80").apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        fun plainRow(labelText: String, valueText: String) {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            r.addView(label(labelText))
            r.addView(
                title(valueText, 14, "#171E2E").apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            fieldCard.addView(r, rowLp())
        }

        // 可展开行：点整行切换面板显隐，箭头 › / ‹ 指示状态
        fun expandableRow(labelText: String, valueText: String, panel: View): TextView {
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
            }
            val value = title(valueText, 14, "#171E2E").apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val arrow = title("›", 16, "#9AA3B2")
            r.addView(label(labelText))
            r.addView(value)
            r.addView(arrow)
            r.setOnClickListener {
                val open = panel.visibility != View.VISIBLE
                panel.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "‹" else "›"
            }
            fieldCard.addView(r, rowLp())
            return value
        }

        fun panelLp(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.bottomMargin = dp(10) }

        fun chipPanel(): Pair<HorizontalScrollView, LinearLayout> {
            val sv = HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                visibility = View.GONE
            }
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            sv.addView(wrap)
            return sv to wrap
        }

        fun chip(text: String, selected: Boolean, onPick: () -> Unit): TextView =
            TextView(ctx).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val r = GradientDrawable()
                r.cornerRadius = dp(16).toFloat()
                r.setColor(Color.parseColor(if (selected) "#1A14B8A6" else "#FFFFFFFF"))
                r.setStroke(
                    Math.max(1, dp(1)),
                    Color.parseColor(if (selected) "#1414B8A6" else "#14000000"),
                )
                background = r
                setTextColor(Color.parseColor(if (selected) "#14B8A6" else "#5A6475"))
                setOnClickListener { onPick() }
            }

        fun addChip(wrap: LinearLayout, view: TextView, margin: Int) {
            wrap.addView(view)
            view.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginEnd = dp(margin) }
        }

        val (accountPanel, accountWrap) = chipPanel()
        val (categoryPanel, categoryWrap) = chipPanel()
        val minorPanel = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val minorWrap = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        minorPanel.addView(minorWrap)

        // ---- 字段行：商户 / 时间 / 账户 / 类目
        plainRow("商户", draft.merchant ?: "（未识别）")
        divider()
        plainRow("时间", SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(draft.occurredAt)))
        divider()

        val effectiveAccountId = draft.accountId ?: guessAccountId(recordedPkg)
        val accountValue = expandableRow("账户", accountName ?: "默认账户", accountPanel)
        fieldCard.addView(accountPanel, panelLp())
        fun renderAccountChips(selectedId: String?) {
            accountWrap.removeAllViews()
            for (acct in allAccounts) {
                val isSel = selectedId == acct.id
                val c = chip(acct.name, isSel) {
                    accountValue.text = acct.name
                    renderAccountChips(acct.id)
                }
                addChip(accountWrap, c, 6)
            }
        }
        renderAccountChips(effectiveAccountId)
        divider()

        val resolved = categories.firstOrNull { it.id == resolvedCategoryId }
        val resolvedName = resolved?.let { it.parentTextOrNull(categories) ?: it.name }
        val categoryValue = expandableRow(
            "类目",
            (emojiPrefix(resolvedName, resolved?.icon) ?: "") + (resolvedName ?: "未分类"),
            categoryPanel,
        )
        var picked = resolved
        fun refreshChips() {
            categoryWrap.removeAllViews()
            minorWrap.removeAllViews()
            minorPanel.visibility = View.GONE
            val majors = categories.filter { it.parentId == null }
            val minorsOf: (String) -> List<CategoryEntity> = { pid ->
                categories.filter { it.parentId == pid }
            }
            for (cat in majors) {
                val selected = picked?.id == cat.id ||
                    (picked == null && resolved?.topParentId(categories) == cat.id)
                val c = chip((emojiPrefix(cat.name, cat.icon) ?: "") + cat.name, selected) {
                    picked = cat
                    pickedCategory = cat
                    categoryValue.text = (emojiPrefix(cat.name) ?: "") + cat.name
                    refreshChips()
                }
                addChip(categoryWrap, c, 8)
            }
            // 细分类行：选中大类且它有子类时出现
            picked?.let { p ->
                val kids = minorsOf(p.id)
                if (kids.isNotEmpty()) {
                    minorPanel.visibility = View.VISIBLE
                    for (kid in kids) {
                        val selected = picked?.id == kid.id
                        val c = chip((emojiPrefix(kid.name, kid.icon) ?: "") + kid.name, selected) {
                            picked = kid
                            pickedCategory = kid
                            categoryValue.text = (emojiPrefix("${p.name}/${kid.name}") ?: "") + "${p.name}/${kid.name}"
                            refreshChips()
                        }
                        addChip(minorWrap, c, 6)
                    }
                }
            }
        }
        refreshChips()
        fieldCard.addView(categoryPanel, panelLp())
        fieldCard.addView(minorPanel, panelLp())

        // ---- 备注：独立输入框 #F5F6FA 圆角 10dp，内边距 12dp
        val noteEdit = EditText(ctx).apply {
            hint = "点此添加备注"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#171E2E"))
            setHintTextColor(Color.parseColor("#9AA3B2"))
            setBackgroundResource(android.R.color.transparent)
            val nb = GradientDrawable()
            nb.cornerRadius = dp(10).toFloat()
            nb.setColor(Color.parseColor("#F5F6FA"))
            background = nb
            setPadding(dp(12), dp(12), dp(12), dp(12))
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
        body.addView(
            noteEdit,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(14) },
        )

        // ---- 底部双按钮：各 50%，圆角 24dp，间距 12dp
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(labelText: String, textColor: String, bgColor: String, stroke: String?, onClick: () -> Unit) {
            val b = TextView(ctx).apply {
                text = labelText
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(textColor))
                val r = GradientDrawable()
                r.cornerRadius = dp(24).toFloat()
                r.setColor(Color.parseColor(bgColor))
                stroke?.let { r.setStroke(Math.max(1, dp(1)), Color.parseColor(it)) }
                background = r
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams(0, dp(48), 1f)
            lp.marginEnd = dp(12)
            b.layoutParams = lp
            buttons.addView(b)
        }
        if (recorded) {
            // 已记录：这笔在账本里已有，问用户要不要再记一次
            button("关闭", "#666F80", "#00000000", "#1A666F80") { removeWindow() }
            button("再记一次", "#FFFFFF", "#14B8A6", null) { onReRecord(draft) }
        } else {
            button("不记了", "#666F80", "#00000000", "#1A666F80") { onDiscard() }
            button("确认入账", "#FFFFFF", "#14B8A6", null) { onConfirm(draft) }
        }
        card.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).also { it.topMargin = dp(20) },
        )
        return card
    }

    // ------------------------------------------------------------ 动作

    /** 不记了：删草稿，什么都不记。已记录模式没有草稿，等于「不记」。 */
    private fun onDiscard() {
        val id = currentDraftId
        if (id == null) {
            removeWindow()
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { container.db.autoDraftDao().delete(id) }
            removeWindow()
        }
    }

    /** 确认入账：落库路径与 DraftsScreen.confirmNow 相同，类目/备注以窗上为准。 */
    private fun onConfirm(draft: AutoDraftEntity) {
        val pickedCategoryFinal = pickedCategory
        val noteFinal = pickedNote ?: draft.note
        // 窗上改过的金额优先；解析失败/非法（空、<=0）退回识别原值
        val amountFinal = pickedAmount
            ?.let { Money.parse(it)?.minor }
            ?.takeIf { it > 0 }
            ?: draft.amountMinor
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
                        amountMinor = amountFinal,
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

    /**
     * 已记录模式的「再记一次」：绕过去重，按窗上信息（金额可改/类目可改/
     * 备注可改）直接入账。入账后落 90 天戳——别再反复提示。
     */
    private fun onReRecord(draft: AutoDraftEntity) {
        val pickedCategoryFinal = pickedCategory
        val noteFinal = pickedNote ?: draft.note
        val amountFinal = pickedAmount
            ?.let { Money.parse(it)?.minor }
            ?.takeIf { it > 0 }
            ?: draft.amountMinor
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val categoryId = pickedCategoryFinal?.id
                        ?: AutoCategory.resolve(
                            db = container.db,
                            ledgerId = draft.ledgerId,
                            merchant = draft.merchant,
                            rawText = draft.rawText,
                            direction = draft.direction,
                        )
                    container.editor.createTransaction(
                        amountMinor = amountFinal,
                        direction = draft.direction,
                        accountId = guessAccountId(recordedPkg),
                        occurredAt = draft.occurredAt,
                        ledgerId = draft.ledgerId,
                        deviceId = container.tokenStore.deviceId,
                        note = noteFinal,
                        merchant = draft.merchant,
                        categoryId = categoryId,
                        source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
                        confidence = draft.confidence,
                    )
                    if (draft.rawText != null) {
                        val key = PaymentId.authoritative(draft.rawText)?.key
                            ?: AutoBook.exactFingerprint(AutoBook.CaptureChannel.A11Y, draft.rawText!!)
                        container.autoBookStore.markFingerprintSeen(key, System.currentTimeMillis())
                    }
                }
                removeWindow()
            }.onFailure {
                android.util.Log.w("BillOverlay", "再记一次失败：${it.javaClass.name}: ${it.message}", it)
            }
        }
    }

    /** 来源 App → 平台级账户。与 AutoCapturePipeline.guessAccountId 同一张映射。 */
    private fun guessAccountId(pkg: String?): String = when (pkg) {
        "com.tencent.mm" -> "A4"
        "com.eg.android.AlipayGphone", "com.alipay.mobile.androidclient", "com.unionpay" -> "A3"
        else -> "A2"
    }

    // ============================================================ 简约模式
    //
    // 弹窗偏好 = 「顶部简约提醒」时走这条通路：**不弹确认窗，直接入账**，
    // 然后在屏幕顶部滑下一条轻提示（分类+金额+账户+时间），底部一行
    // 「今天已记录 N 次，共支出 ¥X · 点击查看」。形态对标商汤咔皮记账
    // 的默认弹窗（2026-10-03 真机实测）——用户被"每进一次详情页就弹
    // 2/3 屏"惹恼过，两种形态都保留，设置里二选一。
    //
    // 入账路径与确认按钮（[onConfirm]）逐字段一致：同样的 AutoCategory
    // 解析、同样的 LocalEditWriter source——只有"谁点的确认"不同。

    /**
     * 自动入账一稿并弹顶部横幅。草稿已被并/删时静默退出（无事可做）。
     * 任何失败都只打日志：草稿还留在待确认页，数据不丢。
     */
    private fun autoPostAndShow(draftId: String) {
        if (!Settings.canDrawOverlays(this)) return
        scope.launch {
            val postedId = withContext(Dispatchers.IO) {
                runCatching {
                    val fresh = container.db.autoDraftDao().byId(draftId)
                        ?: return@runCatching null
                    val categoryId = runCatching {
                        AutoCategory.resolve(
                            db = container.db,
                            ledgerId = fresh.ledgerId,
                            merchant = fresh.merchant,
                            rawText = fresh.rawText,
                            direction = fresh.direction,
                        )
                    }.getOrNull()
                    val txn = container.editor.createTransaction(
                        amountMinor = fresh.amountMinor,
                        direction = fresh.direction,
                        accountId = fresh.accountId ?: "A1",
                        occurredAt = fresh.occurredAt,
                        ledgerId = fresh.ledgerId,
                        deviceId = container.tokenStore.deviceId,
                        note = fresh.note,
                        merchant = fresh.merchant,
                        categoryId = categoryId,
                        source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
                        confidence = fresh.confidence,
                    )
                    container.db.autoDraftDao().delete(fresh.id)
                    txn.id
                }.onFailure {
                    android.util.Log.w(
                        "BillOverlay",
                        "简约模式自动入账失败：${it.javaClass.name}: ${it.message}",
                        it,
                    )
                }.getOrNull()
            } ?: return@launch
            showPosted(postedId)
        }
    }

    /** 入账完成后弹顶部横幅。读不到账目行（不该发生）就什么都不显示。 */
    private fun showPosted(txnId: String) {
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching {
                    val txn = container.db.txnDao().byId(txnId) ?: return@runCatching null
                    val categoryName = txn.categoryId?.let { container.db.categoryDao().byId(it) }?.name
                    val accountName = container.db.accountDao().byId(txn.accountId)?.name
                    val cal = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    val summary = container.db.txnDao().daySummary(
                        LocalEditWriter.SOURCE_LOCAL_FALLBACK,
                        cal.timeInMillis,
                        cal.timeInMillis + 24 * 3600_000L,
                    )
                    Triple(txn, (categoryName ?: "已入账") to accountName, summary)
                }.getOrNull()
            } ?: return@launch
            val (txn, catAcc, summary) = data
            showBannerWindow(buildBanner(txn, catAcc.first, catAcc.second, summary))
        }
    }

    /** 顶部横幅：白卡一条，两行内容 + 当日汇总行，6 秒自动收起。 */
    private fun buildBanner(
        txn: kapi.ledger.data.TxnEntity,
        categoryName: String,
        accountName: String?,
        summary: kapi.ledger.data.DayAutoSummary?,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val ctx = this

        val income = txn.direction == "INCOME"
        val amountText = (if (income) "+¥" else "-¥") +
            java.lang.String.format(Locale.CHINA, "%.2f", txn.amountMinor / 100.0)
        val amountColor = if (income) "#14B8A6" else "#E5484D"
        val subLine = buildString {
            accountName?.let { append(it) }
            append(" · ")
            append(SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(txn.occurredAt)))
            txn.merchant?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        val summaryText = summary?.takeIf { it.n > 0 }?.let {
            "今天已记录 ${it.n} 次，共支出 ¥${java.lang.String.format(Locale.CHINA, "%.2f", it.total / 100.0)}"
        } ?: "今天第 1 笔，点击查看"

        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        outer.setPadding(dp(14), dp(30), dp(14), dp(4))

        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.setPadding(dp(14), dp(10), dp(14), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F7FFFFFF"))
        bg.cornerRadius = dp(20).toFloat()
        bg.setStroke(Math.max(1, dp(1)), Color.parseColor("#14000000"))
        card.background = bg
        card.elevation = dp(6).toFloat()

        fun text(s: String, size: Int, color: String, bold: Boolean = false) = TextView(ctx).apply {
            this.text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
            setTextColor(Color.parseColor(color))
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        val row1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row1.gravity = Gravity.CENTER_VERTICAL
        row1.addView(
            text((emojiPrefix(categoryName) ?: "") + categoryName, 15, "#171E2E", bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row1.addView(text(amountText, 16, amountColor, bold = true))
        card.addView(row1)

        card.addView(
            text(subLine, 12, "#8A93A3").apply {
                setPadding(0, dp(3), 0, 0)
                maxLines = 1
            },
        )

        val footer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        footer.gravity = Gravity.CENTER_VERTICAL
        val dot = View(ctx)
        val dotBg = GradientDrawable()
        dotBg.cornerRadius = dp(3).toFloat()
        dotBg.setColor(Color.parseColor("#14B8A6"))
        dot.background = dotBg
        dot.layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).also { it.marginEnd = dp(6) }
        footer.addView(dot)
        footer.addView(text(summaryText + " · 点击查看", 12, "#5A6475"))
        card.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also {
                it.topMargin = dp(8)
            },
        )

        // 横幅整体可点：打开 App（账本页），横幅收起
        outer.addView(card)
        card.setOnClickListener {
            runCatching {
                packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
            removeWindow()
        }
        return outer
    }

    /** 顶部滑入的横幅窗口：与确认窗共用 [root]/[removeWindow] 的单窗状态。 */
    private fun showBannerWindow(view: View) {
        removeWindow()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 0
        view.translationY = -density * 90
        root = view
        windowManager = wm
        wm.addView(view, params)
        view.animate().translationY(0f).setDuration(240).start()
        handler.postDelayed({ removeWindow() }, BANNER_AUTO_HIDE_MS)
    }

    private fun CategoryEntity.parentTextOrNull(categories: List<CategoryEntity>): String? =
        parentId?.let { pid -> categories.firstOrNull { it.id == pid }?.let { "${it.name}/$name" } } ?: name

    /**
     * 分类名的 emoji 前缀（对标咔皮的彩色分类图标）。emoji 表没有的名字
     * 返回 null，显示退回纯文字——不硬塞问号。
     */
    private fun emojiPrefix(name: String?, icon: String? = null): String? =
        kapi.ledger.ui.CategoryEmoji.glyphOf(name, icon)?.plus(" ")

    private fun CategoryEntity.topParentId(categories: List<CategoryEntity>): String =
        parentId ?: id

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val MODE_CONFIRM = "confirm"
        private const val MODE_RECORDED = "recorded"
        private const val MODE_AUTOPOST = "autopost"
        private const val EXTRA_DRAFT_ID = "draft_id"
        private const val EXTRA_RAW_TEXT = "raw_text"
        private const val EXTRA_OCCURRED_AT = "occurred_at"
        private const val EXTRA_PKG = "pkg"
        private const val AUTO_HIDE_MS = 90_000L

        /** 简约横幅停留时长。确认窗是 90 秒等人操作；横幅没人操作，短停即走。 */
        private const val BANNER_AUTO_HIDE_MS = 6_000L

        /** 管线 Drafted 之后调用（没记录过的账单）。无悬浮窗权限时静默跳过。 */
        fun show(context: Context, draftId: String) {
            val intent = Intent(context, BillOverlayService::class.java)
                .putExtra(EXTRA_MODE, MODE_CONFIRM)
                .putExtra(EXTRA_DRAFT_ID, draftId)
            context.startService(intent)
        }

        /** 去重命中（已记录）之后调用：弹「当前账单已记录」。 */
        fun showRecorded(context: Context, rawText: String, occurredAt: Long, pkg: String?) {
            val intent = Intent(context, BillOverlayService::class.java)
                .putExtra(EXTRA_MODE, MODE_RECORDED)
                .putExtra(EXTRA_RAW_TEXT, rawText)
                .putExtra(EXTRA_OCCURRED_AT, occurredAt)
                .putExtra(EXTRA_PKG, pkg)
            context.startService(intent)
        }

        /**
         * 简约模式（弹窗偏好=顶部简约提醒）入口：草稿**自动入账**并弹顶部横幅。
         * 入账路径与确认按钮完全相同，只是不用人点。
         */
        fun autoPost(context: Context, draftId: String) {
            val intent = Intent(context, BillOverlayService::class.java)
                .putExtra(EXTRA_MODE, MODE_AUTOPOST)
                .putExtra(EXTRA_DRAFT_ID, draftId)
            context.startService(intent)
        }
    }
}
