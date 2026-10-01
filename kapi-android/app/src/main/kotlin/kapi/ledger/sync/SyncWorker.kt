package kapi.ledger.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kapi.ledger.container
import kapi.core.llm.LocalTextParser
import kapi.core.llm.TransactionExtractor

/**
 * 同步 worker。
 *
 * 返回 [Result.retry] 的判据很关键：**只有瞬时故障才重试**。
 * 配对码错、token 失效这类配置问题重试一万次也是一样的结果，
 * 而每次重试都会写库、占网络、耗电，用户看得见的"同步一直转圈"就是这么来的。
 * 配置问题应该立刻停下来，把提示摆到界面上让人去修。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val store = c.tokenStore

        // 没配 NAS 是**正常状态**，不是错误。首次启动的用户全都这样，
        // 每次都报 reday 会让 WorkManager 面板一直显示失败。
        if (!store.isPaired) return Result.success()

        return when (val r = c.syncEngine.sync(store.baseUrl, store.token, store.deviceId)) {
            is SyncEngine.SyncResult.Success -> Result.success()
            is SyncEngine.SyncResult.Rejected -> Result.success()
            is SyncEngine.SyncResult.NotPaired -> Result.success()
            is SyncEngine.SyncResult.Failed -> when (r.failure) {
                // 瞬时：断网、NAS 重启中。退避重试。
                is SyncFailure.Transient -> Result.retry()
                is SyncFailure.Unreachable -> Result.retry()
                // 配置问题：再跑一百遍也是同样结果，立刻停下来把提示摆到界面上。
                is SyncFailure.Unauthorized,
                is SyncFailure.Pairing,
                is SyncFailure.Protocol,
                -> Result.failure()
            }
        }
    }

    companion object {
        /**
         * 共享文字 → 本地规则解析。
         *
         * 这是**零配置**的自动记账入口：用户从任何 App 里「分享到本 App」就记一笔，
         * 不需要连 NAS、不需要配大模型、不联网。BYOK 产品必须有这种永远能用的路，
         * 否则用户第一次 key 失效就永久流失。
         */
        fun parseSharedText(text: String?): kapi.core.llm.TransactionDraft? =
            LocalTextParser.parse(text)?.best
    }
}
