package kapi.ledger.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kapi.core.llm.LlmConfig
import kapi.ledger.llm.LlmClient
import kapi.ledger.llm.LlmFailure
import kapi.ledger.llm.LlmStore
import kotlinx.coroutines.launch

/**
 * 常见端点预设。
 *
 * 只给 baseUrl 和模型名两个占位——**不预置任何 API Key**，这是 BYOK 的定义。
 * 预置第三方 key 看着省事，但意味着发布这个包的人替你付钱，且所有人共用一把
 * key 被风控ban；更糟的是用户会以为那是安全的。
 */
private data class Preset(
    val label: String,
    val baseUrl: String,
    val model: String,
    val visionModel: String,
    val hint: String,
)

private val PRESETS = listOf(
    Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "", "便宜，但不支持图片"),
    Preset("硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-VL-7B-Instruct", "有多个开源模型可选"),
    Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "gpt-4o-mini", "认图"),
    Preset("Ollama 本机", "http://127.0.0.1:11434/v1", "qwen2.5:7b", "qwen2.5vl:7b", "数据完全不出户"),
    Preset("LM Studio 本机", "http://127.0.0.1:1234/v1", "local-model", "", "数据完全不出户"),
)

/** 探测结果。三态而不是布尔：能分清"能连上"和"能认图"是两回事。 */
private sealed interface ProbeState {
    data object Idle : ProbeState
    data object Busy : ProbeState
    data class Ok(val reply: String) : ProbeState
    data class Err(val detail: String) : ProbeState
}

@Composable
fun LlmSettingsScreen(
    store: LlmStore,
    client: LlmClient,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val saved = remember { store.load() }

    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var supportsVision by remember { mutableStateOf(saved.supportsVision) }
    var revealKey by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }

    val draft = LlmConfig(baseUrl, apiKey, model, supportsVision)
    // 三态而不是两态。没配置 ≠ 本地端点：LlmConfig.isLocalEndpoint() 在空地址上
    // 返回 false，空配置要是直接走「非本机」分支，一个 key 都没填的新用户
    // 就会看到红色的"截图会发到这个地址"告警——他压根没填地址。
    val isConfigured = draft.isConfigured
    val isLocal = isConfigured && draft.isLocalEndpoint()
    val isPlaintextCloud = isConfigured && !isLocal && baseUrl.trim().startsWith("http://")

    fun persist() = store.save(draft)

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
    ) {
        // ---- 顶栏 ----
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "返回", tint = Ink) }
            Text(
                "大模型识别",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ---- 数据去向：这页最重要的一块 ----
            //
            // 这个 App 卖的是"数据只在我自己的机器上"。这句话在账单上是真的
            // （账单只走 NAS），但**截图会不会出户取决于用户填的端点**。
            // 不说清楚，用户填完云端 key 还以为全程离线——那就是骗人。
            DataFlowCard(isLocal = isLocal, isPlaintextCloud = isPlaintextCloud, configured = isConfigured)

            Spacer(Modifier.height(16.dp))

            // ---- 预设 ----
            SectionLabel("常见端点")
            PRESETS.forEach { p ->
                val active = baseUrl.trim() == p.baseUrl
                PresetRow(
                    preset = p,
                    selected = active,
                    onClick = {
                        baseUrl = p.baseUrl
                        if (model.isBlank() || PRESETS.any { it.model == model || it.visionModel == model }) {
                            model = p.model
                            supportsVision = p.visionModel.isNotBlank()
                        }
                        probe = ProbeState.Idle
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("自己填")
            LlmField(
                value = baseUrl,
                onChange = { baseUrl = it; probe = ProbeState.Idle },
                label = "接口地址",
                placeholder = "https://api.deepseek.com/v1",
            )
            Spacer(Modifier.height(10.dp))
            LlmField(
                value = model,
                onChange = { model = it; probe = ProbeState.Idle },
                label = "模型名",
                placeholder = "deepseek-chat",
            )
            Spacer(Modifier.height(10.dp))
            LlmField(
                value = apiKey,
                onChange = { apiKey = it; probe = ProbeState.Idle },
                label = "API Key",
                placeholder = "本机端点可以留空",
                visual = if (revealKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    Text(
                        if (revealKey) "隐藏" else "显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand,
                        modifier = Modifier.clickable { revealKey = !revealKey },
                    )
                },
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("这个模型能看图", style = MaterialTheme.typography.bodyLarge, color = Ink)
                    Text(
                        if (supportsVision) "截图识别可用" else "只能用文字识别，截图功能会关掉",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaint,
                    )
                }
                Switch(
                    checked = supportsVision,
                    onCheckedChange = { supportsVision = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Brand,
                    ),
                )
            }

            // ---- 探测 ----
            Spacer(Modifier.height(18.dp))
            when (val p = probe) {
                is ProbeState.Ok -> ProbeBanner(true, "连上了，模型回了：${p.reply.take(40)}")
                is ProbeState.Err -> ProbeBanner(false, p.detail)
                else -> Unit
            }
            if (probe is ProbeState.Ok || probe is ProbeState.Err) Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    persist()
                    probe = ProbeState.Busy
                    scope.launch {
                        probe = try {
                            ProbeState.Ok(client.ping(draft))
                        } catch (e: LlmFailure) {
                            // 读 detail 不读 message：message 可能是 null。
                            Log.e("LlmSettings", "ping failed: ${e.javaClass.name}", e)
                            ProbeState.Err(e.detail)
                        } catch (e: Exception) {
                            Log.e("LlmSettings", "ping failed: ${e.javaClass.name}", e)
                            ProbeState.Err("出错了：${e.javaClass.simpleName}")
                        }
                    }
                },
                enabled = draft.isConfigured && probe !is ProbeState.Busy,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (draft.isConfigured) BrandDeep else InkFaint.copy(alpha = 0.30f),
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            ) { Text(if (probe is ProbeState.Busy) "测试中…" else "测试连接") }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { persist(); onDone() },
                enabled = draft.isConfigured,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (draft.isConfigured) Brand else InkFaint.copy(alpha = 0.30f),
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            ) { Text("保存") }

            if (saved.isConfigured) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "清空",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ExpenseRed,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable { store.clear(); onDone() }
                        .padding(12.dp),
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (store.isEncryptedAtRest()) {
                    "API Key 存在系统加密存储里，不出现在任何日志中。"
                } else {
                    "注意：这台设备拿不到系统密钥库，API Key 目前是明文保存的。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun DataFlowCard(isLocal: Boolean, isPlaintextCloud: Boolean, configured: Boolean) {
    val accent = when {
        !configured -> InkFaint
        isPlaintextCloud -> ExpenseRed
        isLocal -> Brand
        else -> ExpenseRed
    }
    val icon = when {
        !configured -> Icons.Filled.Lock
        isLocal -> Icons.Filled.Lock
        else -> Icons.Filled.Public
    }
    val title = when {
        !configured -> "还没配置"
        isLocal -> "端点在你自己机器上"
        else -> "端点在云端"
    }
    val body = when {
        !configured -> "填完之后截图识别才会启用。在此之前通知栏文本走本地规则识别，不联网。"
        isLocal -> "截图不经过任何第三方。你在 NAS 上跑的模型就是你的模型。"
        isPlaintextCloud ->
            "账单仍然只同步到你的 NAS，但**截图会发到这个地址**，" +
                "而且用的是明文 http——API Key 会以明文过网络。换 https，或者指向本机端点。"
        else ->
            "账单仍然只同步到你的 NAS，但**截图会发到这个地址**。" +
                "介意的话，可以在 NAS 上跑一个本地端点填进来。"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Surface,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                body.replace("**", ""),
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft,
            )
        }
    }
}

@Composable
private fun PresetRow(preset: Preset, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .then(
                if (selected) Modifier.border(1.5.dp, Brand, RoundedCornerShape(12.dp))
                else Modifier
            )
            .glassClickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preset.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = Ink,
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Brand,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(preset.hint, style = MaterialTheme.typography.bodySmall, color = InkFaint)
        }
    }
}

@Composable
private fun ProbeBanner(ok: Boolean, text: String) {
    val accent = if (ok) Brand else ExpenseRed
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = accent)
    }
}

@Composable
private fun LlmField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    placeholder: String,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = visual,
        trailingIcon = trailing,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Brand,
            unfocusedBorderColor = Hairline,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = InkFaint,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
