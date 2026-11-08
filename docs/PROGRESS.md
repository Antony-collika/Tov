# TIẾN ĐỘ

## Môi trường build đã thử
- Có Android SDK? **Không** (biến `ANDROID_HOME` rỗng).
- Lệnh đã thử: `which gradle java kotlinc git` → chỉ có `java` và `git`; không có `gradle`, không có `kotlinc`. Thử truy cập mạng (`curl https://dl.google.com`) không có kết quả (mạng bị tắt).
- Kết quả: **không build được vì thiếu Gradle, Android SDK và mạng.** Không thể chạy `assembleDebug` hay `testDebugUnitTest`. Toàn bộ P0 ở mức **[ĐÃ ĐỌC]**; không có task nào đạt mức [ĐÃ CHẠY].
- Hệ quả: người giao cần để CI (GitHub Actions) hoặc máy có Android SDK build thử và báo lại nếu có lỗi biên dịch. CI hiện chỉ chạy `assembleRelease`; test mới (T0.4) sẽ **không** được CI chạy, cần chạy tay `gradle --no-daemon testDebugUnitTest`.
- Dự án không có git trong gói zip. Agent dùng git cục bộ chỉ để đối chiếu thay đổi, không đưa thư mục `.git` vào gói trả lại. Cột "Commit" bên dưới ghi danh sách file đã đổi thay cho mã commit (R12).

- Lượt làm P1–P2b: môi trường vẫn **không có Gradle/Android SDK/mạng** (đã kiểm `find / -name kotlinc`, không có). Mọi thứ ở mức **[ĐÃ ĐỌC]**, chưa biên dịch.
- **Lỗi CI đã gặp và đã sửa:** `compileReleaseKotlin` báo `Unresolved reference 'MessageEntity'` (và `toolCallsJson`, `toolIsError`, `text`) trong `ui/ChatToolCards.kt`. Nguyên nhân: T0.3 tách file nhưng thiếu `import com.example.byokchat.data.MessageEntity`. Đã thêm đúng 1 dòng import. Các file tách khác (`ChatMessages.kt`, `ChatInputBar.kt`) đã được đọc lại, import đủ.

## Task đã xong
Quy ước: dự án không có git trong gói giao, nên cột "File đã đổi" thay cho mã commit (R12). Mỗi task đã làm trong một commit riêng ở kho git cục bộ của agent (không đưa vào gói).

| Task | File đã đổi | Mức xác minh | Ghi chú |
|---|---|---|---|
| T0.1 | `docs/PROGRESS.md` (mới) | [ĐÃ ĐỌC] | Không có Gradle/SDK/mạng nên không build được. Xem mục môi trường ở trên. |
| T0.2 | `app/src/main/res/values/strings.xml` | [ĐÃ ĐỌC] | `app_name` = `Tov`. `grep` "BYOK" toàn dự án: đây là chỗ duy nhất có chữ hiển thị cũ. Không đụng `rootProject.name`, `applicationId`, `namespace`, `Theme.ByokChat`, `ByokTheme` (R4). |
| T0.3 | `ui/ChatScreen.kt` (bớt 343 dòng); mới: `ui/ChatMessages.kt`, `ui/ChatToolCards.kt`, `ui/ChatInputBar.kt` | [ĐÃ ĐỌC] | Chỉ di chuyển code. Đã đối chiếu bằng script từng dòng code (bỏ package/import): 634 dòng gốc = 634 dòng mới; khác biệt duy nhất là 6 dòng đổi `private` → `internal` (`EditState`, `Actions`, `UserMessage`, `AssistantMessage`, `ToolCards`, `InputBar`). `MessageMenu`, `highlighted`, `EditedLabel`, `MessageEditor` giữ `private` vì chỉ dùng trong `ChatMessages.kt`; `callsSer` giữ `private` trong `ChatToolCards.kt`; `RegenPrompt`, `Match`, `formatTokens` ở lại `ChatScreen.kt`. Import mỗi file tính bằng cách lọc danh sách gốc (chỉ bỏ import mà tên không xuất hiện trong file; giữ nguyên các import dấu `*`). Một vài import thừa (cảnh báo, không lỗi) còn lại, ví dụ `lazy.items` trong `ChatMessages.kt`. |
| T0.4 | `gradle/libs.versions.toml`, `app/build.gradle.kts`; mới: `app/src/test/java/com/example/byokchat/util/TextUtilTest.kt` | [ĐÃ ĐỌC] (test: **[CHƯA KIỂM CHỨNG]** chưa chạy) | Thêm đúng 2 thư viện ở mục 2.4; giữ `compose.icons.core`. Test có 7 hàm. Vì không có Kotlin nên đã **mô phỏng thuật toán `findAll` bằng Python** (cùng chuẩn Unicode) để kiểm 15 giá trị mong đợi: đều khớp. Đây **không** phải chạy test thật. CI hiện chỉ chạy `assembleRelease`, không chạy test. |
| T0.5 | `ui/ProviderEditScreen.kt` | [ĐÃ ĐỌC] | Thêm `supportingText` dưới ô "Địa chỉ máy chủ" khi địa chỉ bắt đầu `http://` (không phân biệt hoa/thường) và API key không rỗng. Không tắt `usesCleartextTraffic`, không chặn lưu, không đổi logic mạng. Hồi quy liên quan: H11 (lưu nhà cung cấp) đọc lại, không đổi. |
| T0.6 | `docs/SIGNING_PROPOSAL.md` (mới) | Tài liệu | Chỉ viết đề xuất; **không** sửa code ký, không đụng `signing/`. |
| Sửa lỗi CI | `ui/ChatToolCards.kt` | [ĐÃ ĐỌC] | Thêm 1 dòng import `MessageEntity`. Chờ CI chạy lại để xác nhận. |
| T1.1 | `ai/Http.kt`; mới `test/.../ai/HttpErrorTest.kt` | [ĐÃ ĐỌC] (test: [CHƯA KIỂM CHỨNG]) | `errorMessage` trả câu thân thiện + `\nChi tiết: Lỗi <mã>: <gốc>`. Lỗi mạng ở 2 chỗ `catch IOException` đổi sang `networkErrorMessage`, giữ nhánh `else { throw e }`. `grep` xác nhận không có nơi nào phân tích chuỗi lỗi. |
| T1.2 | `ui/Dialogs.kt`, `ui/ProviderEditScreen.kt` | [ĐÃ ĐỌC] | `FetchedModelsDialog(all, alreadyAdded, onAdd, onDismiss)` dùng chung; hành vi giữ nguyên. |
| T1.3 | mới `ui/OnboardingScreen.kt`; sửa `data/SettingsRepository.kt`, `ui/AppNav.kt`, `ui/ConversationListScreen.kt` | [ĐÃ ĐỌC] | Màn chào 3 bước, trạng thái trong `OnboardingViewModel`. Cờ `onboarding_done` trong prefs `settings`. |
| T1.4 | `data/ProviderConfig.kt` (`pinnedModels` có mặc định), `ui/ChatViewModel.kt` (`togglePin`), `ui/ChatScreen.kt`, `ui/ChatInputBar.kt`, `ui/Dialogs.kt` (xóa `ModelPickerDialog`), `ui/AppNav.kt`; mới `ui/ModelPickerSheet.kt` | [ĐÃ ĐỌC] | Không cần Migration (cấu hình nằm trong prefs mã hóa, trường mới có giá trị mặc định). Đã bỏ nút cờ-lê và dòng phụ dưới tiêu đề. |
| T1.5 | mới `ui/ContextRing.kt`; `ui/ChatScreen.kt` (`formatTokens` đổi `private` → `internal`) | [ĐÃ ĐỌC] | Ngưỡng màu: <70% primary, 70–90% tertiary, >90% error. |
| T1.6 | mới `ui/ChatEmptyState.kt`; `ui/ChatScreen.kt`, `ui/ChatInputBar.kt` (thêm `FocusRequester` để đưa con trỏ vào ô nhập) | [ĐÃ ĐỌC] | 3 trạng thái: chưa có AI / chưa chọn mô hình / gợi ý. |
| T2.1 | `ui/ChatMessages.kt`, `ui/ChatScreen.kt` | [ĐÃ ĐỌC] | Hàng nút dưới tin cuối mỗi lượt; chạm giữ bong bóng tin của bạn (menu neo vào bong bóng). |
| T2.2 | mới `ui/SelectTextSheet.kt`; `ui/ChatMessages.kt` | [ĐÃ ĐỌC] | Không bật `setTextIsSelectable`. |
| T2.3 | mới `ui/MarkdownSegments.kt`, `ui/CodeBlock.kt`, `test/.../ui/MarkdownSegmentsTest.kt`; `ui/MarkdownText.kt` (`MarkdownBody`) | [ĐÃ ĐỌC] (test: [CHƯA KIỂM CHỨNG]) | Đã đối chiếu tay 13 ca test với thuật toán. Khi đang tìm kiếm dùng đường cũ (H6). |
| T2.4 | mới `ui/ImageViewerDialog.kt`; `ui/ChatMessages.kt`, `ui/ChatInputBar.kt` | [ĐÃ ĐỌC] | Cử chỉ phóng tự viết, chỉ "chiếm" thao tác khi 2 ngón hoặc đang phóng. |
| T2.5 | `ui/ChatScreen.kt` | [ĐÃ ĐỌC] | Nút hiện khi `firstVisibleItemIndex > 1`. |
| T2.6 | `ui/ChatMessages.kt` | [ĐÃ ĐỌC] | Thẻ lỗi: câu thân thiện + dòng "Chi tiết" nhạt + nút Thử lại; menu ⋮ của tin lỗi chỉ còn Sao chép/Xóa. |
| T2.7 | `AndroidManifest.xml`, `App.kt`, `MainActivity.kt`, `chat/ChatEngine.kt`, `ui/AppNav.kt`, `ui/ChatScreen.kt`, `data/SettingsRepository.kt`; mới `chat/ChatNotifier.kt`, `res/drawable/ic_stat_tov.xml` | [ĐÃ ĐỌC] ([CHƯA KIỂM CHỨNG] trên thiết bị) | Thông báo khi xong/lỗi lúc app không hiện; không thông báo khi bấm Dừng. Xin quyền một lần ở lần gửi tin đầu (Android 13+). |
| T2.8 | `AndroidManifest.xml`, `App.kt`; mới `chat/GenerationService.kt` | [ĐÃ ĐỌC] ([CHƯA KIỂM CHỨNG] trên thiết bị) | Dịch vụ loại dataSync, bật khi có cuộc đang trả lời. `ChatEngine.run` không đổi logic (chỉ T2.7 chèn lời gọi thông báo ở các điểm kết thúc). |

Ghi chú về quy trình: gói giao không có git nên không có commit; các task làm tuần tự theo đúng thứ tự, cột "File đã đổi" thay cho commit (R12). Một số file được nhiều task cùng sửa (`ChatScreen.kt`, `ChatMessages.kt`, `ChatInputBar.kt`), nên T2.1 và T2.6 được viết thẳng ở trạng thái cuối (menu tin lỗi không còn "Tạo lại").

Hồi quy đã rà bằng cách đọc code sau T0.3 (không đổi hành vi, chỉ đổi vị trí code): H1–H8, H13 (phần chat/tin nhắn/ảnh/tìm kiếm). Tất cả ở mức [ĐÃ ĐỌC].

## Task bỏ qua / bị chặn (lý do)
- **T0.6 phần đổi code ký**: [CẦN QUYẾT ĐỊNH]. Chờ người giao trả lời (xem `docs/SIGNING_PROPOSAL.md`, mục 5).
- Không có task nào bị bỏ qua trong P1–P2b. Người giao đã đồng ý rõ ràng cho P2b (thêm 3 quyền: `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`) và yêu cầu làm liền P1→P2b trong một lượt.

## Quyết định đã đưa ra (chỉ những điều tài liệu cho phép tự quyết)
- T0.5: chọn màu `error` (tài liệu cho phép `tertiary` hoặc `error`) cho dòng cảnh báo http; điều kiện "API key không rỗng" tính bằng `isNotBlank()`.
- T0.3: chỉ nâng sang `internal` đúng 6 khai báo mà file khác cần dùng.

- T1.3: nếu máy chủ trả danh sách mô hình rỗng, bước 2 báo lỗi dễ hiểu và hướng dẫn "Bỏ qua" rồi thêm tay (tài liệu không nói trường hợp này).
- T1.3: để mốc 20 giây thật sự có tác dụng (lệnh mạng chặn luồng không tự dừng khi hết giờ), dùng `supervisorScope` + `async` rồi `withTimeout(20_000) { job.await() }`; hết giờ thì hủy `job`. Vẫn đúng yêu cầu "bọc trong withTimeout".
- T1.3: thoát màn chào bằng `popBackStack("list")`, nếu không có `list` trong ngăn xếp (mở app lần đầu) thì `navigate("list") { popUpTo("onboarding") { inclusive = true } }`. Cách này tránh hai màn danh sách chồng nhau khi mở màn chào từ danh sách/chat.
- T1.4: mục "Tất cả" trong khung chọn mô hình liệt kê mọi mô hình (kể cả đã ghim); icon ghim dùng ngôi sao (`Star`/`StarBorder`).
- T2.8: khi hết cuộc trả lời, đợi 1,5 giây rồi mới `stopService` (và hủy nếu có cuộc mới) để tránh lỗi hệ thống khi bấm Dừng ngay sau khi vừa bật dịch vụ. Nếu hệ thống từ chối bật dịch vụ nền, lý do được ghi vào `container.foregroundServiceError` và `Log.w("Tov", …)`, việc trả lời vẫn tiếp tục.

## Phát hiện thêm ngoài phạm vi (không sửa)
- `chat/ChatEngine.kt` còn câu hướng dẫn "bấm biểu tượng mô hình ở đầu màn hình chat" (2 chỗ). Sau T1.4 biểu tượng đó không còn; nên đổi thành "bấm nhãn mô hình phía trên ô nhập".
- CI chỉ chạy `assembleRelease`; `HttpErrorTest`, `MarkdownSegmentsTest`, `TextUtilTest` chưa từng được chạy.
- Màu nền khối code dùng `surfaceContainerHigh` (Material3 1.3.x); nếu CI báo không tìm thấy thì đổi sang `surfaceVariant`.
- `Icons.Default.CallSplit` có thể báo cảnh báo "deprecated" (không phải lỗi) vì thư viện icon mới có bản `AutoMirrored`.
- File kế hoạch nhận được **kết thúc ở P2b**; không có P3–P6, T4.x, Phụ lục B (bảng màu đầy đủ). Các phần đó được nhắc tới ở nhiều chỗ (T4.1, T4.7, T4.8, T6.6, Phụ lục B) nhưng chưa có nội dung.
- Quy trình CI (`.github/workflows/android.yml`) chỉ chạy `assembleRelease`, không chạy `testDebugUnitTest`, nên test mới sẽ không tự chạy.
- `signing/tov-development.p12.base64` và mật khẩu khóa nằm trong kho mã (đã nêu ở T0.6).
