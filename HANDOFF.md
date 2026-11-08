# HANDOFF / PROGRESS – Tov (app chat AI BYOK, Android, Kotlin + Compose)

> File này để người (hoặc phiên Claude khác) đọc lại là nắm được: đã làm gì, quyết định gì, còn gì, test gì.
> Cập nhật lần cuối: sau **Đợt 1**.

## 1. Nguồn yêu cầu và thứ tự ưu tiên
- `specs` (có từ trước dự án): chỉ để tham khảo, cái gì là *feature* chứ không phải lỗi. Vẫn phải dựa vào code.
- **Khảo sát cải thiện UI/UX = nhu cầu hiện tại (sau khi có MVP) → ƯU TIÊN KHẢO SÁT** khi hai bên lệch nhau.
- Chỗ lệch specs đã xử lý theo khảo sát:
  - Menu tin nhắn của bạn: bỏ "Regenerate" riêng (chỉ còn Copy / Edit message / Edit & regenerate / Delete).
  - Bỏ nút ⋮ ở tin nhắn, chỉ nhấn giữ (long press) để mở menu (làm ở Đợt 2).
  - Placeholder ô nhập "Ask anything", màn trống chỉ "Hi, Welcome" (Đợt 2).

## 2. Quy ước đã chốt với người dùng
- Giao **từng đợt (1→4)**; người dùng build, báo lỗi, rồi làm đợt tiếp.
- **Chuẩn hóa tiếng Anh áp dụng cho CẢ giao diện LẪN mô tả/kết quả công cụ gửi cho AI** (tên/mô tả mặc định của công cụ, thông báo lỗi trả về cho model, thông báo hệ thống). Làm ở Đợt 3. Code mới viết từ Đợt 1 đã dùng tiếng Anh.
- Người dùng không phải lập trình viên → giải thích đơn giản, không lạm dụng thuật ngữ.
- Môi trường của Claude **không có Android SDK/Gradle → chưa từng build thử**. Mọi thay đổi mới chỉ được rà bằng mắt (cân bằng ngoặc, tham chiếu). Nếu lỗi build, người dùng dán log để sửa.

## 3. Tiến độ
- [x] **Đợt 1 – Cấu trúc & P0** (xem mục 4)
- [ ] Đợt 2 – P1 màn chat (mục 6)
- [ ] Đợt 3 – P1 danh sách / cài đặt / công cụ + Anh hóa toàn app (mục 7)
- [ ] Đợt 4 – P2 làm đẹp (mục 8)

## 4. Đợt 1 – đã làm gì

### 4.1 Điều hướng mới
- Mở app vào **thẳng màn chat** (một cuộc trò chuyện mới). Danh sách hội thoại nằm trong **Drawer**.
- Vuốt phải (hoặc bấm icon menu ở góc trái top bar) để mở Drawer.
- **Nút Back ở màn chat → mở Drawer.** Nếu Drawer đang mở *do bấm Back* thì Back lần nữa sẽ **thoát app** (tránh vòng lặp mở/đóng). Nếu mở bằng cách khác thì Back đóng Drawer. Back khi đang tìm trong cuộc → đóng thanh tìm trước.
- **Branch / Regenerate-in-branch** chỉ tạo một cuộc trò chuyện bình đẳng rồi chuyển sang nó; **không còn ngăn xếp điều hướng lùi qua từng nhánh**.
- File: `ui/AppNav.kt` (viết lại; giữ `chatId`, `focusMsg`, `initialQuery`, `branchFrom` bằng `rememberSaveable`), `ui/ChatHost.kt` (mới: Drawer + ChatScreen, `key(conversationId)` để mỗi cuộc có state riêng), `ui/ConversationDrawer.kt` (thay `ConversationListScreen.kt`, đã xóa).
- Search: bấm kết quả → đặt cuộc + từ khóa + tin cần cuộn tới rồi quay về màn chat (không điều hướng "tới" thêm một màn chat nữa).
- Thông báo "AI đã trả lời": mở đúng cuộc trò chuyện (cơ chế `pendingConversation` giữ nguyên, nay gọi `openChat`).

### 4.2 P0.1 – Snackbar "Hoàn tác" không còn đè ô nhập
- `InputBar` chuyển sang slot `bottomBar` của `Scaffold` (snackbar tự nằm trên nó).
- Vì là bottomBar, `InputBar` tự xử lý inset: `Modifier.navigationBarsPadding().imePadding()` (đúng thứ tự này).
- Đã bỏ `imePadding()` ở Column nội dung.

### 4.3 P0.2 – Hiệu năng khi AI đang stream
- `ChatEngine` có `liveText: StateFlow<Map<messageId, text>>` (cập nhật mỗi ~80ms) – text đang stream nằm trong **bộ nhớ**.
- Ghi DB giảm từ 120ms xuống **mỗi 1000ms** (+ ghi cuối khi xong/dừng/lỗi). Giá trị live bị xóa sau khi ghi bản cuối.
- `ChatScreen` thu `liveText` nhưng **không đọc ở cấp màn hình** (không dùng `by`); chỉ item tin đang stream đọc `liveText.value[m.id]` → chỉ item đó recompose. `AssistantMessage` có tham số `liveText`.
- `MarkdownText`: **một Markwon dùng chung** qua `LocalMarkwon` (tạo bằng `createMarkwon(context)` trong `AppNav`), không tạo mỗi tin một instance.

### 4.4 P0.3 – Không còn cuộc trò chuyện rỗng chất đống
- `ConversationRepository.create()` gọi `deleteEmpty()` trước (xóa mọi cuộc không có tin nhắn) rồi mới tạo cuộc mới.
- Danh sách chỉ hiện cuộc **có ít nhất 1 tin nhắn** (`observeNonEmpty()`); tìm kiếm cũng bỏ cuộc rỗng.
- "New chat" khi đang ở cuộc rỗng → không làm gì (đã là chat mới).
- Mỗi lần mở app (cold start) tạo một chat mới (và dọn cuộc rỗng cũ).

### 4.5 P0.4 – Thông báo khi AI chờ xác nhận công cụ
- `ConfirmationBroker`: thêm `id` cho từng yêu cầu, hook `onRequested` / `onFinished`, `resolveById`.
- `AppContainer.init`: nếu app **không ở foreground** khi có yêu cầu xác nhận → `ChatNotifier.notifyConfirmation` (kênh `confirm`, độ ưu tiên cao) với nút **Allow / Deny**; khi yêu cầu xong thì tự xóa thông báo.
- **Thao tác xóa (Risk.DELETE): thông báo chỉ có nút Deny**, muốn cho phép phải mở app đọc chi tiết (đúng tinh thần specs "xóa luôn hỏi" – chủ động an toàn hơn khảo sát một chút).
- `chat/ConfirmReceiver.kt` (mới) + khai báo `<receiver>` trong `AndroidManifest.xml`.
- Nếu app bị hệ thống tắt giữa chừng, thông báo cũ có thể còn sót; bấm nút sẽ không làm gì (vuốt để xóa).

### 4.5b P0.5 – Branch có phản hồi
- DB lên **version 2** có Migration 1→2 (`ALTER TABLE conversations ADD COLUMN parentId TEXT` và `pinned INTEGER NOT NULL DEFAULT 0`) – dữ liệu cũ giữ nguyên. Cột `pinned` để sẵn cho Đợt 3 (ghim).
- Cuộc nhánh có `parentId`; tên dạng `"<tên gốc> (branch)"` (không lồng "(branch) (branch)").
- Sau khi branch: snackbar **"Branch created" + nút "Original chat"** (quay về cuộc gốc).
- Drawer: cuộc nhánh hiện icon nhánh + "Branch of <tên cuộc gốc>".

### 4.6 Danh sách file thay đổi trong Đợt 1
Mới: `ui/ChatHost.kt`, `ui/ConversationDrawer.kt`, `chat/ConfirmReceiver.kt`, `HANDOFF.md`.
Xóa: `ui/ConversationListScreen.kt`.
Sửa: `AndroidManifest.xml`, `App.kt`, `chat/ChatEngine.kt`, `chat/ChatNotifier.kt`, `chat/ChatOps.kt`, `data/AppDatabase.kt`, `data/ConversationRepository.kt`, `data/Daos.kt`, `data/Entities.kt`, `tools/Brokers.kt`, `ui/AppNav.kt`, `ui/ChatInputBar.kt`, `ui/ChatMessages.kt`, `ui/ChatScreen.kt`, `ui/ChatViewModel.kt`, `ui/MarkdownText.kt`, `ui/SearchScreen.kt`.

## 5. Checklist test Đợt 1 (người dùng chạy thử)
1. Mở app → vào thẳng chat trống; vuốt phải mở Drawer; Back mở Drawer; Back lần nữa thoát app.
2. Gửi vài tin → cuộc xuất hiện trong Drawer. Bấm "New chat" nhiều lần liên tiếp → **không** sinh thêm cuộc rỗng; thoát app mở lại không thấy cuộc "Cuộc trò chuyện mới" thừa.
3. Xóa một lượt → snackbar "Hoàn tác" nằm **trên** ô nhập, không che nút gửi.
4. Hỏi AI một câu trả lời dài: gõ vào ô nhập và cuộn trong lúc AI đang viết → mượt hơn trước.
5. Branch một tin AI → chuyển sang cuộc mới, snackbar "Branch created · Original chat"; Back → mở Drawer (không lùi qua các nhánh); Drawer hiện icon nhánh + tên gốc.
6. Bật "Hỏi trước khi làm" cho một công cụ ghi file, nhờ AI ghi file rồi **thoát ra màn hình chính** → có thông báo kèm Allow/Deny; bấm Allow thì AI chạy tiếp.
7. Cập nhật từ bản cũ: dữ liệu hội thoại cũ vẫn còn (migration v1→v2).
8. Xoay màn hình khi đang ở một cuộc trò chuyện: vẫn ở đúng cuộc đó.

## 6. Kế hoạch Đợt 2 – P1 màn chat (CHƯA LÀM)
- **InputBar mới**: "+" (dropdown giữ nguyên), tên mô hình (chuỗi ID thô, không đổi sang tên đẹp), nút gửi – cùng một khối/hàng *bên dưới* khung nhập. Placeholder chỉ "Ask anything".
- **Menu tin nhắn = Bottom sheet mở bằng long press, bỏ nút ⋮**:
  - User: Copy | Edit message | Edit & regenerate | Delete.
  - Assistant: Copy | Edit message | Branch in new chat | Regenerate | Delete.
  - (Giữ thêm "Select text" vì khảo sát nói Chọn chữ phải luôn dùng được – xác nhận lại với người dùng nếu cần.)
- Hàng nút dưới câu trả lời **chỉ hiện ở câu trả lời cuối cùng** (Copy, Regenerate, Branch); lượt cũ dùng long press.
- **Copy / Chọn chữ không bị khóa khi AI đang trả lời** (chỉ khóa sửa/xóa/tạo lại, đúng specs).
- Gửi tin → `animateScrollToItem(0)`.
- Top bar: dấu hiệu khi cuộc có system prompt tùy chỉnh; vòng ngữ cảnh >90% gợi ý "Start a new chat from here".
- Tìm trong cuộc: Enter nhảy sang kết quả kế tiếp. Nút X xóa ảnh chờ gửi: vùng chạm 48dp.
- Toast "Copied" chỉ hiện trên Android < 13.
- Lỗi từ AI: nút đúng ngữ cảnh ("Open provider settings") bên cạnh "Retry".
- Màn trống: chỉ "Hi, Welcome" (bỏ các gợi ý); vẫn giữ thẻ "chưa có AI / chưa chọn mô hình".
- Thẻ công cụ: câu dễ đọc ("Read file notes/todo.md"), JSON trong phần mở rộng, trạng thái đang chạy rõ.
- Hộp xác nhận: thêm "Allow all in this turn" và "Always allow this tool" (không áp dụng cho Delete).

## 7. Kế hoạch Đợt 3 – danh sách / cài đặt / công cụ + Anh hóa (CHƯA LÀM)
- **Anh hóa toàn app** (UI + mô tả công cụ + kết quả/lỗi công cụ + thông báo + tên kênh thông báo + `ThemeMode.label`, `ToolMode.label`, `ToolGroup.label`...). Các file còn tiếng Việt nhiều: `OnboardingScreen`, `ProviderEditScreen`, `ProvidersScreen`, `SettingsScreen`, `ToolsScreen`, `NotesScreen`, `ModelPickerSheet`, `ChatMessages`, `ChatScreen`, `ChatToolCards`, `ConfirmationHost`, `Dialogs`, `ContextRing`, `SearchScreen`, `SelectTextSheet`, `ImageViewerDialog`, `CodeBlock`, `ChatEmptyState`, `tools/*Tools.kt`, `tools/ToolRegistry.kt`, `tools/WorkspaceFs.kt`, `ai/*Provider.kt`, `ai/Http.kt`, `chat/ChatEngine.kt`, `chat/GenerationService.kt`, `data/*`, `util/Text.kt`. Lưu ý các test (`HttpErrorTest`, `TextUtilTest`, `MarkdownSegmentsTest`) có thể chứa chuỗi tiếng Việt cần cập nhật theo.
  - Cần chú ý `DEFAULT_TITLE = "Cuộc trò chuyện mới"`: đổi sang "New chat" nhưng vẫn nhận ra tiêu đề cũ để đặt tên tự động.
- Drawer: dòng phụ = xem trước tin cuối; nhóm Today / Yesterday / Previous 7 days / Older; vuốt để xóa + Undo; nhấn giữ đổi tên/xóa; **ghim** (cột `pinned` đã có); **chọn nhiều để xóa hàng loạt**; thanh tìm ngay trong danh sách và gom kết quả theo cuộc.
- Cài đặt: chia nhóm AI / Tools / Data & workspace / Appearance; theme dùng nút chọn ngang 3 lựa chọn; workspace hiện tên thư mục dạng tương đối (`./homework`); đổi nút "Kiểm tra" → "Check read/write access".
- Quản lý công cụ: dòng phụ là mô tả ngắn thay UID; ô tìm; công tắc/chế độ theo nhóm; màn chi tiết gộp công tắc + chip thành điều khiển 3 đoạn Auto | Ask | Off; BackHandler cảnh báo khi sửa tên/mô tả dở.

## 8. Kế hoạch Đợt 4 – P2 (CHƯA LÀM)
- Bảng màu/ font / bo góc riêng + fallback cho máy cũ (Dynamic Color vẫn dùng khi có).
- Chỉ báo "Thinking" + ba chấm động; hiệu ứng xuất hiện nhẹ cho tin mới; haptic nhẹ khi gửi/dừng.
- Tìm kiếm toàn cục bằng FTS của Room (vẫn bỏ dấu tiếng Việt như hiện có) – cần migration DB v3.

## 9. Ghi chú kỹ thuật cần nhớ
- Room `version = 2`, `exportSchema = false`, chỉ có `MIGRATION_1_2` (không dùng destructive migration). Thêm cột/bảng mới → viết migration mới.
- `ConversationRepository.create()` luôn dọn cuộc rỗng; đừng gọi nó "dự phòng" khi người dùng đang đứng trong một cuộc rỗng khác.
- `ChatScreen` được bọc `key(conversationId)` trong `ChatHost` và `viewModel(key = conversationId)` – mỗi cuộc có ViewModel riêng.
- Nếu lỗi build ở Đợt 1, các chỗ dễ sai nhất: `ChatHost.kt` (API Drawer của Material3 1.3.x), `ChatNotifier.kt` (import `Risk`, `ConfirmRequest`), `ConversationDrawer.kt` (`ListItemDefaults.colors`), `ChatScreen.kt` (tham số mới), `AppDatabase.kt` (migration).
