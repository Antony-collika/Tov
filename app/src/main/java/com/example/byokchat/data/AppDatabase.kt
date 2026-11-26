package com.example.byokchat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, NoteEntity::class, TodoEntity::class, RecentlyPlayedEntity::class, SubRunEntity::class],
    version = 14,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun notes(): NoteDao
    abstract fun todos(): TodoDao
    abstract fun recentlyPlayed(): RecentlyPlayedDao
    abstract fun subRuns(): SubRunDao

    companion object {
        /** v1 -> v2: branch parent and pinned flag on conversations (existing data is kept). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN parentId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v4 -> v5 (Đợt 4): số lượt ổn định `turnNo` trên messages. Hàng cũ được
         * điền lại theo thứ tự lượt: số của một lượt = 1 + số lượt khác nhau
         * trong cùng cuộc trò chuyện mà lượt đầu tiên của chúng đến trước nó
         * (so theo seq nhỏ nhất của lượt). Xóa lượt không làm số còn lại đổi,
         * và lượt mới luôn lấy max + 1 nên số đã xóa không bao giờ dùng lại.
         * (Bảng đích phải có bí danh m0: trong SQL lồng nhau, tên `messages`
         * không bí danh sẽ trỏ vào bảng của SELECT trong cùng, không phải
         * dòng đang UPDATE — không bí danh thì mọi hàng đều nhận số 1.)
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN turnNo INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE messages AS m0 SET turnNo = 1 + (" +
                        "SELECT COUNT(DISTINCT m2.turnId) FROM messages m2 " +
                        "WHERE m2.conversationId = m0.conversationId " +
                        "AND (SELECT MIN(m3.seq) FROM messages m3 WHERE m3.turnId = m2.turnId) < " +
                        "(SELECT MIN(m4.seq) FROM messages m4 WHERE m4.turnId = m0.turnId))"
                )
            }
        }

        /**
         * v5 -> v6 (Đợt fix 12/11/2026): chữ ký suy luận của Gemini cho phần
         * chữ của tin assistant (`thoughtSignature` trên messages, null =
         * không có / không phải Gemini). Hàng cũ để null — request sau chỉ
         * gửi lại chữ ký khi có, không lỗi.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN thoughtSignature TEXT")
            }
        }

        /**
         * v6 -> v7 (Đợt provider 12/11/2026): suy luận của provider cho tin
         * assistant — `reasoningText` (chuỗi đọc được, hiện ở khối "Suy luận"
         * + Preview), `reasoningReplay` (JSON gửi lại nguyên vẹn: output
         * items của Responses), `reasoningProvider`
         * (hãng đã sinh ra suy luận: "openai" / "custom" / "deepseek" —
         * "deepseek" là dữ liệu cũ, nay DeepSeek dùng qua Custom; "anthropic"
         * là dữ liệu cũ, hãng đó đã bỏ — app bỏ qua khi đọc).
         * Hàng cũ để null — request sau chỉ gửi lại khi có, không lỗi.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoningText TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoningReplay TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoningProvider TEXT")
            }
        }

        /**
         * v7 -> v8 (2026-11-14): bảng `recently_played` — "Recently played"
         * lưu vào DB để không mất khi app bị kill (trước đây chỉ giữ trong
         * bộ nhớ của phiên chạy). Không có dữ liệu cũ để chuyển (chưa từng lưu).
         * Xóa cuộc trò chuyện thì các hàng này xóa theo (CASCADE).
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recently_played` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`replayKey` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recently_played_conversationId` " +
                        "ON `recently_played` (`conversationId`)",
                )
            }
        }

        /**
         * v8 -> v9 (Phase 2 sub-agents, 2026-11-15): `sender` trên messages —
         * ai gửi một tin assistant (null = Main, còn lại là id sub-agent).
         * Hàng cũ để null — tin cũ vẫn là của Main.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN sender TEXT")
            }
        }

        /**
         * v9 -> v10 (fork history khi regenerate, 2026-11-15): `historyBranch`
         * trên conversations — nhánh file history hiện tại (0 = history.md gốc,
         * N ≥ 1 = history_(NN).md). Hàng cũ để 0 — file gốc vẫn là bản hiện tại.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN historyBranch INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v10 -> v11 (context manager, 17/11/2026): table `sub_runs` — one
         * stored record per sub-agent run, so Main can read a past run back
         * and the run detail screen can show it. CREATE TABLE only: no old
         * data is touched or moved, and old runs simply have no row here
         * (the detail screen falls back to the turn's message rows). There
         * is no destructive fallback since v5 (Glassbox rule, see
         * create() below): a version bump without a migration would crash
         * existing installs on open, and wiping the database would destroy
         * the user's chats — both worse than this create-only migration,
         * which is exactly the "no migration" Duy accepted (no old data
         * migrated, new table only, new chats only).
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sub_runs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "`turnNo` INTEGER NOT NULL, " +
                        "`agent` TEXT NOT NULL, " +
                        "`agentName` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`brief` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`rounds` INTEGER NOT NULL, " +
                        "`tokens` INTEGER NOT NULL, " +
                        "`toolCallsJson` TEXT NOT NULL, " +
                        "`messagesToMainJson` TEXT NOT NULL, " +
                        "`fullReport` TEXT NOT NULL, " +
                        "`reasoning` TEXT NOT NULL, " +
                        "`error` TEXT, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`finishedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sub_runs_conversationId` " +
                        "ON `sub_runs` (`conversationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sub_runs_conversationId_agent_turnNo` " +
                        "ON `sub_runs` (`conversationId`, `agent`, `turnNo`)",
                )
            }
        }

        /**
         * v11 -> v12 (Phase 4 async subs, 18/11/2026): `messages.taskTitle` —
         * the task a sub-agent's late message belongs to, for the "↩ title"
         * anchor above it. Nullable, no old data touched: every existing
         * row reads null, only new late messages carry a title.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN taskTitle TEXT")
            }
        }

        /**
         * v12 -> v13 (Main inbox + peer attribution, 20/11/2026): `startedBy`
         * / `startedByName` on sub_runs — who started the run ("main" for
         * Main, else the delegating/waking sub-agent's id). Old rows read
         * "main"/"Main": before peer mode every run was Main-delegated,
         * so the default is exactly right and no old data is touched.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sub_runs ADD COLUMN startedBy TEXT NOT NULL DEFAULT 'main'")
                db.execSQL("ALTER TABLE sub_runs ADD COLUMN startedByName TEXT NOT NULL DEFAULT 'Main'")
            }
        }

        /**
         * v13 -> v14 (@mention state, 26/11/2026): `mentionAgentId` on
         * messages — which sub-agent a user message directly mentions
         * (null = Main). Old rows read null: mentions only exist from
         * this build on, and every old message was addressed to Main.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN mentionAgentId TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "byokchat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                // Không còn fallbackToDestructiveMigration(): từ v5 trở đi mọi
                // thay đổi cấu trúc đều phải có migration thật (quy tắc kế hoạch
                // Glassbox, mục 6 Đợt E). v2 -> v3 (bỏ systemPrompt) và v3 -> v4
                // (bỏ providerId/model của từng cuộc) vẫn không có migration:
                // bản cài đang ở v2/v3 khi nâng cấp lên v6 sẽ báo lỗi thay vì
                // bị xóa sạch DB — gỡ app và cài lại trong trường hợp đó.
                .build()
    }
}
