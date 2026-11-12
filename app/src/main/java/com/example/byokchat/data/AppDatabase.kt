package com.example.byokchat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, NoteEntity::class, TodoEntity::class],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun notes(): NoteDao
    abstract fun todos(): TodoDao

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
         * items của Responses / khối thinking của Claude), `reasoningProvider`
         * (hãng đã sinh ra suy luận: "openai" / "deepseek" / "anthropic").
         * Hàng cũ để null — request sau chỉ gửi lại khi có, không lỗi.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoningText TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoningReplay TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoningProvider TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "byokchat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                // Không còn fallbackToDestructiveMigration(): từ v5 trở đi mọi
                // thay đổi cấu trúc đều phải có migration thật (quy tắc kế hoạch
                // Glassbox, mục 6 Đợt E). v2 -> v3 (bỏ systemPrompt) và v3 -> v4
                // (bỏ providerId/model của từng cuộc) vẫn không có migration:
                // bản cài đang ở v2/v3 khi nâng cấp lên v6 sẽ báo lỗi thay vì
                // bị xóa sạch DB — gỡ app và cài lại trong trường hợp đó.
                .build()
    }
}
