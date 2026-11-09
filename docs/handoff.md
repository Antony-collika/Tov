# Handoff – Tối ưu hiệu năng Tov (Android, Kotlin + Compose)

> Đọc file này để biết: đã sửa gì, vì sao, cần kiểm tra gì, và nếu build lỗi thì xem ở đâu.
> Nguồn yêu cầu: `Hướng dẫn thực thi tối ưu hóa Tov app` (09/11/2026). Chi tiết từng task: `docs/PROGRESS.md`, mục «Đợt tối ưu hiệu năng».
> File này nằm trong `docs/` vì thư mục gốc đã có `HANDOFF.md` (cùng tên khi không phân biệt hoa thường).

## 1. Trạng thái (quan trọng đọc trước)
- **Chưa build, chưa chạy test.** Môi trường làm việc không có Gradle, Android SDK hay mạng. Mọi thay đổi chỉ được rà bằng đọc code: cân bằng ngoặc, tên hàm, import, chữ ký, kiểu dữ liệu.
- Đã làm đủ danh sách: **A1–A7, B1, B2, C1–C3**. Không làm gì ngoài danh sách (mục 5 của hướng dẫn).
- Đã sửa thêm một lỗi có sẵn: hai file test (`ConversationSearchTest`, `ConversationGroupsTest`) truyền trường đã bị xóa khỏi `ConversationEntity`, nên test không biên dịch được.

## 2. Việc cần làm ngay khi nhận gói
1. Build: `gradle --no-daemon assembleRelease` (CI cũng chạy việc này).
2. Test: `gradle --no-daemon testDebugUnitTest`. Đây là bước CI không tự chạy.
3. Nếu lỗi biên dịch, các chỗ dễ sai nhất là:
   - `ui/MarkdownText.kt`: viết lại toàn bộ. Kiểm tra `Markwon.setParsedMarkdown` và `toMarkdown` có đúng chữ ký của Markwon 4.6.2 không.
   - `tools/WorkspaceFs.kt`: `children()` dùng `ContentResolver.query` và các hằng `DocumentsContract.Document.*`; `companion object` với `ThreadLocal<Job?>`.
   - `tools/ToolRegistry.kt`: `WorkspaceFs.cancelJob.asContextElement(...)` (import `kotlinx.coroutines.asContextElement`).
   - `chat/ChatOps.kt`: `db.withTransaction { return@withTransaction }` (import `androidx.room.withTransaction`).
   - `ai/ClaudeProvider.kt`: `JsonObject(el + (...))` và `buildMessages(msgs, useCache)`.
   - `data/SettingsRepository.kt`: khối `init` nằm **cuối** class, có chủ đích (xem mục 4).
   - `ui/AppNav.kt`: `return` giữa hàm composable, sau `if (!ready)`.
4. Nếu build được nhưng test đỏ, xem `ConversationSearchTest.searchAllChatsGivesTheSameResultAsBuildSearchGroups` và `TextUtilTest.fastFoldMatchesReferenceForEveryCharacter`.

## 3. Kiểm tra bằng tay trên máy (checklist từ mục 6.3 của hướng dẫn)
- Gửi tin, nhận câu trả lời dài có bảng và khối code; dừng giữa chừng.
- Tìm trong chat và tìm toàn bộ, bằng chữ **có dấu** và **không dấu** (ví dụ «điều» và «dieu»).
- Nhấn giữ vào chữ của tin AI (vẫn mở menu); nhấn giữ tin của bạn.
- Xóa, tạo lại, rẽ nhánh (Branch), hoàn tác (Undo).
- Đính kèm ảnh; gửi lại cùng ảnh nhiều lần.
- Công cụ thư mục: liệt kê, tìm, ghi, nén, giải nén. Bấm **Dừng** khi đang tìm trong tệp hoặc giải nén: phải dừng trong vài giây.
- Mở app lần đầu và lần sau: không thấy màn chào hay thẻ «No AI connected» chớp lên với người dùng cũ.
- Mở app từ thông báo: vẫn đúng cuộc trò chuyện.
- Chế độ hỏi xác nhận: thẻ công cụ và hộp xác nhận vẫn hoạt động.

## 4. Những thay đổi cần biết (dễ gây nhầm)
- **Khởi tạo kho mã hóa (`SettingsRepository`)**: nhà cung cấp và API key đọc ở luồng nền. Trước khi đọc xong (`settings.ready == false`), `providers` là danh sách rỗng. `saveProvider`, `deleteProvider`, `setSearchApiKey` tự gọi `ensureSecureLoaded()` để không ghi đè bằng danh sách rỗng. Đừng đọc `providers` ở chỗ chạy trước `AppNav` hiển thị.
- **Số liệu «Chat memory»** không còn tính liên tục. Hàm `computeTokenStats()` chỉ chạy khi hộp thoại mở, và chạy lại khi danh sách tin đổi.
- **Ghi nháp tin AI**: `updateText` chỉ cập nhật cột `text` của một dòng, không ghi lại cả dòng.
- **Cache Markdown** (`MarkdownText.kt`): chỉ cache khi không đang stream, không tô sáng tìm kiếm, và không có `|`, `[ ]`, `[x]` (bảng và danh sách việc cần). Tin đang stream và đủ dài (≥1500 ký tự) được phân tích ở luồng nền.
- **Bộ nhớ đệm metadata thư mục** (`WorkspaceFs`): xóa ở đầu mỗi lần chạy công cụ và sau mỗi lần ghi. Nếu thấy kết quả liệt kê cũ, kiểm tra xem có chỗ ghi nào quên `clearMeta()`.
- **Dừng công cụ**: `WorkspaceFs.cancelJob` là `ThreadLocal`, được gắn vào luồng công việc của công cụ bằng `asContextElement`. Mọi vòng lặp dài trong công cụ phải gọi `env.fs.stopIfCancelled()`.
- **Prompt caching Claude** chỉ bật khi `baseUrl` rỗng hoặc chứa `anthropic.com`. Với địa chỉ khác, nội dung yêu cầu giữ nguyên như trước.
- **Ảnh**: cache base64 khóa theo đường dẫn + kích thước + ngày sửa; ảnh gửi đi không đổi kích thước hay chất lượng.

## 5. Sai lệch nhỏ so với hướng dẫn (đã cân nhắc)
- Đặt thêm `!streaming` vào điều kiện cache Markdown (hướng dẫn không nói).
- `fs.grep` đếm «đã quét» theo tệp đọc thành công (xem `PROGRESS.md`).
- `fetch_url`: trang trên 600 000 ký tự bị cắt sớm, nên số ký tự trong thông báo cắt bớt có thể khác.

## 6. Vấn đề ngoài phạm vi, chưa sửa (để người phụ trách quyết định)
- **Khóa ký nằm trong kho mã**: `signing/tov-development.p12.base64` và mật khẩu viết thẳng trong `app/build.gradle.kts`. Đây là rủi ro bảo mật, xem `docs/SIGNING_PROPOSAL.md`.
- **Có một file `.whl` lạc** trong `app/src/main/java/com/example/byokchat/` (thư viện tree-sitter của Python). Không ảnh hưởng build Kotlin nhưng nên gỡ khỏi gói.
- Chuỗi hướng dẫn trong `chat/ChatEngine.kt` còn nhắc «biểu tượng mô hình ở đầu màn hình» (đã ghi trong PROGRESS từ trước).

## 7. Tệp đã đổi (31 tệp)
- Engine và dữ liệu: `chat/ChatEngine.kt`, `chat/ChatOps.kt`, `data/Daos.kt`, `data/Entities.kt`, `data/ConversationRepository.kt`, `data/SettingsRepository.kt`
- Giao diện: `ui/ChatViewModel.kt`, `ui/ChatScreen.kt`, `ui/ChatMessages.kt`, `ui/ChatToolCards.kt`, `ui/ConversationDrawer.kt`, `ui/ConversationSearch.kt`, `ui/MarkdownText.kt` (viết lại), `ui/MarkdownSegments.kt`, `ui/AppNav.kt`
- Công cụ: `tools/WorkspaceFs.kt`, `tools/FsTools.kt`, `tools/TextTools.kt`, `tools/ToolRegistry.kt`, `tools/DailyTools.kt`
- AI: `ai/Http.kt`, `ai/ImageStore.kt`, `ai/ClaudeProvider.kt`, `ai/OpenAiProvider.kt`, `ai/GeminiProvider.kt`
- Khác: `App.kt`, `util/Text.kt`
- Test: `util/TextUtilTest.kt`, `ui/ConversationSearchTest.kt`, `ui/ConversationGroupsTest.kt`, `ui/MarkdownSegmentsTest.kt`
- Tài liệu: `docs/PROGRESS.md` (thêm mục đợt tối ưu), `docs/handoff.md` (file này)
