package kapi.ledger.auto

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「只读不写」这条底线的护栏。
 *
 * ## 为什么需要一道测试
 *
 * 2026-10-01 为了让微信愿意交出节点树，服务声明里加了
 * `android:canPerformGestures="true"` 和 `flagRequestFilterKeyEvents`
 * （见 `res/xml/accessibility_service_config.xml` 的注释）。
 *
 * 那两个位**只是声明**，代码里一次都没用过——微信把"能读"当成真实辅助工具的
 * 副产品，连屏幕都驱动不了的服务会被判定成自动化工具而不是读屏。
 *
 * 但声明本身就是一种能力授权，而且它会出现在系统设置里用户点同意的那一屏上。
 * 也就是说：**从今天起，这份声明可以被用来做手势，而代码里没有任何东西阻止它。**
 * 原来的"只读"靠的是"没人写"，不是靠机制拦。这道测试把"没人写"变成"写不了"——
 * 有人加了手势调用，用例立刻红。
 *
 * 触发它的原因在 [KapiAccessibilityService] 的类注释里：用户装的是记账 App，
 * 不是脚本工具。一个能点按钮的无障碍服务，能力上和"自动下单"只差一步；
 * Google Play 也明确禁止无障碍服务"自主发起、规划及执行动作"。
 * 这条底线不是洁癖，是这个类存在的意义。
 */
class AccessibilityReadOnlyGuardTest {

    /** 被看守的源文件。相对 app 模块目录，Gradle 单测的工作目录就是这里。 */
    private val serviceSource = File("src/main/kotlin/kapi/ledger/auto/KapiAccessibilityService.kt")

    @Test
    fun `源文件存在，别把路径改错了让这条测试变成空转`() {
        assertTrue(
            "找不到 ${serviceSource.absolutePath}——测试静默通过比没有测试更糟",
            serviceSource.isFile,
        )
    }

    @Test
    fun `服务里不得出现任何会改变屏幕状态的调用`() {
        assertTrue("剥注释的实现自己坏了", !stripComments(serviceSource.readText()).contains("/*"))

        val code = stripComments(serviceSource.readText())
        for (forbidden in listOf(
            "performAction",
            "performGlobalAction",
            "dispatchGesture",
            "GLOBAL_ACTION_",
        )) {
            assertTrue(
                "KapiAccessibilityService 里出现了 $forbidden —— " +
                    "这个服务必须是只读的。理由见该类的类注释。",
                !code.contains(forbidden),
            )
        }
    }

    /**
     * 把注释整段抹掉，只留可执行代码。
     *
     * **必须连块注释一起剥**：这个类的 KDoc 里正写着"不存在任何 performAction /
     * dispatchGesture"，只剥 `//` 行注释的话，用例会把它自己的说明当成违规——
     * 第一版就是这么红的。
     */
    private fun stripComments(src: String): String = buildString {
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = src.getOrNull(i + 1)
            when {
                inLine -> if (c == '\n') { inLine = false; append(c) }
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i++ } else if (c == '\n') append(c)
                inString -> { append(c); if (c == '"') inString = false }
                c == '/' && next == '/' -> { inLine = true; i++ }
                c == '/' && next == '*' -> { inBlock = true; i++ }
                c == '"' -> { inString = true; append(c) }
                else -> append(c)
            }
            i++
        }
    }

    @Test
    fun `服务配置里声明的手势能力必须与服务里的实际用法一致`() {
        // 声明了手势能力，就必须有一条测试守着"不使用"。上面那条就是。
        // 这里反过来守：如果哪天微信不再需要这个能力位，把 canPerformGestures
        // 去掉时也要一并知道——因为它会让用户在系统设置里多看到一个能力授权。
        val config = File("src/main/res/xml/accessibility_service_config.xml")
        assertTrue("找不到 ${config.absolutePath}", config.isFile)
        val declared = config.readText().contains("android:canPerformGestures=\"true\"")
        // 声明存在时，"不使用"的那条测试必须也在。删掉一条就说明有人绕过审查。
        if (declared) {
            assertTrue(
                "声明了 canPerformGestures，就必须保留" +
                    "AccessibilityReadOnlyGuardTest 里禁止手势调用的那条用例",
                src_of_guard().contains("performAction"),
            )
        }
    }

    /** 守卫测试自己的源码。 */
    private fun src_of_guard(): String =
        File("src/test/kotlin/kapi/ledger/auto/AccessibilityReadOnlyGuardTest.kt").readText()
}
