# TIẾN ĐỘ

## Môi trường build đã thử
- Có Android SDK? **Không** (biến `ANDROID_HOME` rỗng).
- Lệnh đã thử: `which gradle java kotlinc git` → chỉ có `java` và `git`; không có `gradle`, không có `kotlinc`. Thử truy cập mạng (`curl https://dl.google.com`) không có kết quả (mạng bị tắt).
- Kết quả: **không build được vì thiếu Gradle, Android SDK và mạng.** Không thể chạy `assembleDebug` hay `testDebugUnitTest`. Toàn bộ P0 ở mức **[ĐÃ ĐỌC]**; không có task nào đạt mức [ĐÃ CHẠY].
- Hệ quả: người giao cần để CI (GitHub Actions) hoặc máy có Android SDK build thử và báo lại nếu có lỗi biên dịch. CI hiện chỉ chạy `assembleRelease`; test mới (T0.4) sẽ **không** được CI chạy, cần chạy tay `gradle --no-daemon testDebugUnitTest`.
- Dự án không có git trong gói zip. Agent dùng git cục bộ chỉ để đối chiếu thay đổi, không đưa thư mục `.git` vào gói trả lại. Cột "Commit" bên dưới ghi danh sách file đã đổi thay cho mã commit (R12).

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

Hồi quy đã rà bằng cách đọc code sau T0.3 (không đổi hành vi, chỉ đổi vị trí code): H1–H8, H13 (phần chat/tin nhắn/ảnh/tìm kiếm). Tất cả ở mức [ĐÃ ĐỌC].

## Task bỏ qua / bị chặn (lý do)
- **T0.6 phần đổi code ký**: [CẦN QUYẾT ĐỊNH]. Chờ người giao trả lời (xem `docs/SIGNING_PROPOSAL.md`, mục 5).
- **P1, P2a, P2b chưa làm**: tài liệu quy định mỗi lượt giao việc chỉ làm một giai đoạn, người giao không nói khác. P2b còn cần người giao đồng ý rõ ràng vì thêm quyền Android.

## Quyết định đã đưa ra (chỉ những điều tài liệu cho phép tự quyết)
- T0.5: chọn màu `error` (tài liệu cho phép `tertiary` hoặc `error`) cho dòng cảnh báo http; điều kiện "API key không rỗng" tính bằng `isNotBlank()`.
- T0.3: chỉ nâng sang `internal` đúng 6 khai báo mà file khác cần dùng.

## Phát hiện thêm ngoài phạm vi (không sửa)
- File kế hoạch nhận được **kết thúc ở P2b**; không có P3–P6, T4.x, Phụ lục B (bảng màu đầy đủ). Các phần đó được nhắc tới ở nhiều chỗ (T4.1, T4.7, T4.8, T6.6, Phụ lục B) nhưng chưa có nội dung.
- Quy trình CI (`.github/workflows/android.yml`) chỉ chạy `assembleRelease`, không chạy `testDebugUnitTest`, nên test mới sẽ không tự chạy.
- `signing/tov-development.p12.base64` và mật khẩu khóa nằm trong kho mã (đã nêu ở T0.6).
