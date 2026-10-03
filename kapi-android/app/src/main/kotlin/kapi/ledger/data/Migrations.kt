package kapi.ledger.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 迁移。
 *
 * ## 这里**绝不能**用 `fallbackToDestructiveMigration()`
 *
 * 那是"版本对不上就把整个库删掉重建"。对一个记账 App 来说，这等于**用户的全部
 * 账本在一次发版后消失**，而且没有任何提示。而触发它的条件（我加了一张新表、
 * 版本从 1 跳到 2）在这个项目的节奏下是每周都会发生的事。
 *
 * 所以每加一张表就写一条迁移，并且加完必须**在真机上从旧版本升上来验证**——
 * 编译通过不代表 SQL 对得上，Room 是在打开库的那一刻才校验的。
 *
 * ## 迁移 SQL 必须和 entity 声明一字不差
 *
 * 下面是照着 Room 自己导出的 `schemas/2.json` 抄的，不是手写推测的。抄错一个
 * 字段名，现象是"能打开库但一查这张表就崩"，而不是打开时直接失败——比完全
 * 失败更难查。
 */
object Migrations {

    /**
     * 1 → 2：加 `auto_draft`（待确认草稿）。
     *
     * 纯新增表和索引，**不碰任何已有表**。这是最省心的一类迁移：老用户的数据
     * 一行都不动，也就不存在"升级之后总额变了"这种事故。
     */
    val M1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `auto_draft` (
                    `id` TEXT NOT NULL,
                    `ledgerId` TEXT NOT NULL,
                    `amountMinor` INTEGER NOT NULL,
                    `direction` TEXT NOT NULL,
                    `occurredAt` INTEGER NOT NULL,
                    `merchant` TEXT,
                    `note` TEXT,
                    `accountId` TEXT,
                    `source` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `originPackage` TEXT,
                    `rawText` TEXT,
                    `sources` TEXT NOT NULL,
                    `evidenceCount` INTEGER NOT NULL,
                    `likelyDuplicateOf` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_auto_draft_fingerprint` ON `auto_draft` (`fingerprint`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_auto_draft_createdAt` ON `auto_draft` (`createdAt`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_auto_draft_amountMinor_direction_occurredAt` " +
                    "ON `auto_draft` (`amountMinor`, `direction`, `occurredAt`)",
            )
        }
    }

    val M2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 预算表（2026-10-03 咔皮对齐 P1）。纯新增表，不碰已有数据。
            // 建表 SQL 照 Room 导出的 schemas/3.json 抄，字段名错一个字的现象
            // 是"能打开库但一查这张表就崩"——装机后必须真机点一遍预算页。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `budget` (
                    `id` TEXT NOT NULL,
                    `ledgerId` TEXT NOT NULL,
                    `month` TEXT NOT NULL,
                    `categoryId` TEXT,
                    `amountMinor` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_budget_ledgerId_month` ON `budget` (`ledgerId`, `month`)",
            )
        }
    }

    val M3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 用户分类规则 + 账单标签（2026-10-03 咔皮对齐）。纯新增表。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `category_rule` (
                    `id` TEXT NOT NULL,
                    `ledgerId` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `pattern` TEXT NOT NULL,
                    `categoryId` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_category_rule_ledgerId` ON `category_rule` (`ledgerId`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `txn_tag` (
                    `txnId` TEXT NOT NULL,
                    `tag` TEXT NOT NULL,
                    PRIMARY KEY(`txnId`, `tag`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_tag_tag` ON `txn_tag` (`tag`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_txn_tag_txnId` ON `txn_tag` (`txnId`)")
        }
    }

    /**
     * 4 → 5：txn 加内容判重索引（2026-10-04）。
     *
     * sameMerchantAmountDay（同商户+同金额+同方向+同发生日）是读屏热路径：
     * 详情页每 1.5 秒一次的重复判定都要跑它。此前只有 occurredAt 单列索引，
     * Room 选不上，等于每次全表扫描——账目到几千条时每次判定都是一次
     * 毫秒级扫描，攒在主线程协程里就是无谓的卡顿。纯加索引，不碰数据。
     */
    val M4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_txn_merchant_occurredAt` " +
                    "ON `txn` (`merchant`, `occurredAt`)",
            )
        }
    }

    val ALL: Array<Migration> = arrayOf(M1_2, M2_3, M3_4, M4_5)
}
