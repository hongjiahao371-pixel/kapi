package kapi.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kapi.core.auto.MerchantLearning
import kapi.ledger.AppContainer
import kapi.ledger.data.LedgerRepository
import kapi.ledger.data.MerchantCategoryRow
import kotlinx.coroutines.flow.first

/**
 * 分类规则页（2026-10-03，咔皮对齐 P1 第一阶段：**只读**）。
 *
 * 自动记账的分类能力一直存在——[kapi.core.auto.CategoryRules] 的内置规则表
 * 加 [MerchantLearning] 的自学习——但用户**看不见**：分类错了只能逐笔改，
 * "它到底学会了什么"是个黑盒。咔皮把这个做成显式功能（识别规则：交易方/
 * 消费内容两个维度，带效果预览），这一页先兑现"看得见"：
 *
 * 列出**学到的商户归类**——同一商户（归一化后）有 ≥2 笔落在同一分类时，
 * 自动记账就会按它分类；这里把这些规则全部摊开给用户看。
 *
 * 编辑/新增规则是第二阶段：要一张持久化的规则表 + 分类选择器 +
 * 在 AutoCategory 里接线，体量单独一轮。内置规则表（几百个关键词）刻意
 * 不在这里展示——那是实现细节不是用户配置，全摊出来是噪音。
 */
@Composable
fun RulesScreen(
    container: AppContainer,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rows by remember { mutableStateOf<List<LearnedRule>>(emptyList()) }

    LaunchedEffect(Unit) {
        val raw: List<MerchantCategoryRow> =
            container.db.txnDao().merchantCategoryHistogram()
        // 分类名映射：一次拉全量
        val allCategories = container.db.categoryDao()
            .observeForLedger(LedgerRepository.DEFAULT_LEDGER_ID)
            .first()
        val nameOf = allCategories.associate { it.id to it.name }
        val parentOf = allCategories.filter { it.parentId != null }.associate { it.id to it.parentId!! }
        fun label(id: String): String {
            val cat = nameOf[id] ?: return "已删除的分类"
            val pid = parentOf[id]
            return if (pid != null) "${nameOf[pid] ?: "?"}/$cat" else cat
        }

        // 原文级 → 归一化商户级 → 聚优（与 AutoCategory 的判定逻辑同一套函数）。
        // 只展示**定型**的（learned=true）：没到 2 笔同档的不算"规则"，摊出来是噪音。
        // 占位/状态行（加载中、等待对方确认收钱、转账-标题）不是商户，一并滤掉。
        val grouped = raw
            .groupBy { MerchantLearning.normalize(it.merchant) ?: it.merchant }
            .map { (key, rowsOfMerchant) ->
                val dominant = MerchantLearning.decide(rowsOfMerchant.map { it.categoryId to it.n })
                val total = rowsOfMerchant.sumOf { it.n }
                val displayName = rowsOfMerchant.minByOrNull { it.merchant.length }?.merchant ?: key
                LearnedRule(
                    merchant = displayName,
                    category = dominant?.let { label(it.first) } ?: "未定型",
                    count = total,
                    learned = dominant != null,
                )
            }
            .filter { it.learned && !isJunkMerchant(it.merchant) }
            .sortedWith(compareByDescending<LearnedRule> { it.count }.thenBy { it.merchant })
        rows = grouped
    }

    val contentPadding = PaddingValues(top = 48.dp, bottom = 32.dp)
    Column(modifier = modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("完成") }
            Text(
                "分类规则",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(64.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "自动记账除了内置词表，还会从你确认过的账里学：同一商户归过同一档两次，" +
                "以后就按那一档分。下面是它当前学到的全部规则。",
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(10.dp))

        if (rows.isEmpty()) {
            Text(
                "还没有学到的规则——多用几天，或在待确认里把分类改对，它就会开始学。",
                style = MaterialTheme.typography.bodyMedium,
                color = InkFaint,
                modifier = Modifier.padding(12.dp),
            )
            return
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.size) { i ->
                val r = rows[i]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.merchant,
                            style = MaterialTheme.typography.labelLarge,
                            color = Ink,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${r.count} 笔",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkFaint,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("→ ", style = MaterialTheme.typography.labelMedium, color = InkFaint)
                        Text(
                            r.category,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (r.learned) BrandDeep else InkSoft,
                        )
                    }
                }
            }
        }
    }
}

/** 一条展示行：商户 → 分类。 [learned]=false 表示证据还不够，自动记账还没采用。 */
private data class LearnedRule(
    val merchant: String,
    val category: String,
    val count: Int,
    val learned: Boolean,
)

/**
 * 解析器会往 merchant 字段里塞的**非商户**：加载占位、转账页标题、收付款状态行。
 * 它们进了 txn 表就也会进直方图，规则页把它们滤掉（分类那边有占位豁免，无碍）。
 */
private fun isJunkMerchant(name: String): Boolean {
    val n = name.trim()
    if (n.isEmpty()) return true
    if (n.contains("加载中") || n.contains("加载失败") || n.contains("未知商户")) return true
    if (n.startsWith("转账") || n.startsWith("等待对方")) return true
    if (n == "退款" || n == "收款" || n == "付款") return true
    return false
}
