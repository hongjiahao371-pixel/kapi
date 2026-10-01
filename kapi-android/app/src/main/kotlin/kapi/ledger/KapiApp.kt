package kapi.ledger

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kapi.ledger.sync.SyncWorker
import java.util.concurrent.TimeUnit

class KapiApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    // WorkManager 的配置来源。
    //
    // 实现这个接口的作用不是「自定义配置」，而是**把启动顺序依赖从 manifest 里
    // 解耦出来**：只要 Application 是 Configuration.Provider，哪怕
    // androidx.startup 的 InitializationProvider 因为任何原因没跑（被 manifest
    // 合并摘掉、厂商 ROM 改了点初始化顺序、以后真要改成按需初始化），
    // WorkManager.getInstance() 自己会按需初始化，不再抛
    // IllegalStateException。历史上就是在这里闪退过一次：
    // 「摘掉默认 initializer + onCreate 里裸调 getInstance」= 每次开 App 必崩。
    // 两条路（manifest 自动初始化 / 按需初始化）都成立，才叫稳。
    // 注意：2.10 的 Configuration.Provider 已经是 Kotlin 接口，成员是 val
    // 不是 getXxx() 函数——写成 override fun 会直接编译失败。
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(
                if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    Log.DEBUG
                } else {
                    Log.INFO
                },
            )
            .build()

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(this)

        // 同步调度失败**绝不能**让 App 起不来。
        //
        // 记账是纯本地功能，NAS 同步只是锦上添花。工作区里已经因为「摘掉
        // WorkManager 自动初始化却没手动 initialize()」导致过一次启动闪退——
        // 一个可选功能的初始化问题，把整个 App 连同用户所有账本一起锁死，
        // 代价和收益完全不成比例。所以这里整个包起来，失败只记日志。
        try {
            scheduleSync()
        } catch (t: Throwable) {
            Log.e(TAG, "同步调度初始化失败，App 继续以纯本地模式运行", t)
            // 但也不能就这么算了：调度失败 = 用户配好的 NAS 同步**永远不会跑**，
            // 界面上看不出来，只会觉得「同步是坏的」。所以启动完成后重试一次。
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    scheduleSync()
                    Log.i(TAG, "同步调度重试成功")
                } catch (t2: Throwable) {
                    Log.e(TAG, "同步调度重试仍失败，NAS 同步不会自动执行", t2)
                }
            }, RETRY_DELAY_MS)
        }
    }

    private fun scheduleSync() {
        val wm = WorkManager.getInstance(this)

        // ---- 同步调度 ----
        //
        // 「人在外面正常记账，回家连上 WiFi 自动同步」是这个产品的核心设定，
        // 所以**主路径不是定时器，是网络约束**：
        //
        //   UNMETERED 的 PeriodicWork —— 回家连上非计费 WiFi，系统保证会跑。
        //     占日常同步的 90% 以上。
        //
        // 刻意**不用**前台常驻服务：为了几分钟的同步把一个常驻通知挂在用户
        // 屏幕上，代价（耗电 + 用户的信任）远大于收益。
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        wm.enqueueUniquePeriodicWork(
            SYNC_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS,
                )
                .build(),
        )

        // 记账后 5 分钟推一把：人在外面如果正好连着 WiFi，就顺手同步掉了。
        wm.enqueueUniqueWork(
            SYNC_ONCE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build(),
        )
    }

    companion object {
        private const val TAG = "KapiApp"
        const val SYNC_PERIODIC = "kapi-sync-periodic"
        const val SYNC_ONCE = "kapi-sync-once"

        // 退避到启动流程彻底结束之后再试：此时 ContentProvider 初始化、Room
        // 打开这些前置动作都已经完成，不会再和启动抢资源。
        private const val RETRY_DELAY_MS = 1_500L
    }
}

val Context.container: AppContainer
    get() = (applicationContext as KapiApp).container
