# HANDOFF / PROGRESS – Tov (app chat AI BYOK, Android, Kotlin + Compose)

> File này để người (hoặc phiên Claude khác) đọc lại là nắm được: đã làm gì, quyết định gì, còn gì, test gì.
> Cập nhật lần cuối: sau **Đợt 3**.

## 1. Nguồn yêu cầu và thứ tự ưu tiên
- `specs` (có từ trước dự án): chỉ để tham khảo, cái gì là *feature* chứ không phải lỗi. Vẫn phải dựa vào code.
- **Khảo sát cải thiện UI/UX = nhu cầu hiện tại (sau khi có MVP) → ƯU TIÊN KHẢO SÁT** khi hai bên lệch nhau.
- Chỗ lệch specs đã xử lý theo khảo sát:
  - Menu tin nhắn của bạn: bỏ "Regenerate" riêng (chỉ còn Copy / Edit message / Edit & regenerate / Delete). **Ngoại lệ (Đợt 2):** lượt *chưa có câu trả lời nào* của AI (bị hủy/ngắt) vẫn hiện "Regenerate" trong menu để còn thử lại được, đúng specs mục 9.
  - Bỏ nút ⋮ ở tin nhắn, chỉ nhấn giữ (long press) để mở menu → **đã làm ở Đợt 2**.
  - Placeholder ô nhập "Ask anything", màn trống chỉ "Hi, Welcome" → **đã làm ở Đợt 2**.

## 2. Quy ước đã chốt với người dùng
- Giao **từng đợt (1→4)**; người dùng build, báo lỗi, rồi làm đợt tiếp.
- **Chuẩn hóa tiếng Anh áp dụng cho CẢ giao diện LẪN mô tả/kết quả công cụ gửi cho AI** (tên/mô tả mặc định của công cụ, thông báo lỗi trả về cho model, thông báo hệ thống). Làm ở Đợt 3. Code mới viết từ Đợt 1 đã dùng tiếng Anh.
- Người dùng không phải lập trình viên → giải thích đơn giản, không lạm dụng thuật ngữ.
- Môi trường của Claude **không có Android SDK/Gradle → chưa từng build thử**. Mọi thay đổi mới chỉ được rà bằng mắt (cân bằng ngoặc, tham chiếu). Nếu lỗi build, người dùng dán log để sửa.

## 3. Tiến độ
- [x] **Đợt 1 – Cấu trúc & P0** (xem mục 4)
- [x] **Đợt 2 – P1 màn chat** (mục 6, checklist test ở mục 6.5)
- [x] **Đợt 3 – P1 danh sách / cài đặt / công cụ + Anh hóa toàn app** (mục 7, checklist test ở mục 7.5)
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

## 6. Đợt 2 – đã làm gì (P1 màn chat)

### 6.1 InputBar mới (`ui/ChatInputBar.kt`, viết lại)
- Một khối bo tròn: khung nhập ở trên; **hàng bên dưới** gồm "+" (dropdown giữ nguyên: Choose from gallery / Take photo), **tên mô hình**, nút gửi/dừng.
- Placeholder chỉ "Ask anything". Tên mô hình là chuỗi ID thô; khi có **từ 2 nhà cung cấp trở lên** vẫn giữ tiền tố `Tên nhà cung cấp · ` như cũ (xem mục 6.6 – cần người dùng xác nhận).
- Nút X xóa ảnh chờ gửi: vùng chạm **48dp** (hình tròn vẽ 22dp nằm trong IconButton 48dp; ô ảnh cao 76dp để không bị cắt).

### 6.2 Menu tin nhắn = bottom sheet mở bằng nhấn giữ (`ui/MessageMenu.kt` mới, `ui/ChatMessages.kt` viết lại)
- **Đã bỏ hoàn toàn nút ⋮** (cả tin của bạn lẫn tin AI). Nhấn giữ bong bóng (tin bạn) hoặc bất kỳ chỗ nào của tin AI → bottom sheet, có rung nhẹ.
- Tin của bạn: Copy | Select text | Edit message | Edit & regenerate | Delete (+ Regenerate chỉ khi lượt đó chưa có câu trả lời nào, tham số `canRegen`).
- Tin AI: Copy | Select text | Edit message | Branch in new chat | Regenerate | Delete. Tin lỗi: Copy/Select (nếu có chữ) | Regenerate | Delete (không có Edit/Branch).
- **Khóa khi AI đang trả lời:** chỉ Edit / Edit & regenerate / Branch / Regenerate / Delete bị mờ (kèm dòng giải thích trong sheet). **Copy và Select text luôn dùng được.** Copy tin đang stream lấy **text đang hiện** (live), không phải bản DB cũ tới 1 giây. "Select text" chụp snapshot lúc mở nên không đổi nội dung dưới tay khi AI còn viết.
- `Actions.copy` giờ nhận `String` (không phải `MessageEntity`).

### 6.3 Hàng nút dưới câu trả lời chỉ ở **câu trả lời cuối cùng của cả cuộc**
- Gồm Copy | Regenerate | Branch (không còn ⋮). Điều kiện: tin cuối cùng đang hiển thị là tin AI, có chữ, `status == "done"` (`lastAnswerId` trong `ChatScreen`). Lượt cũ dùng nhấn giữ.
- Copy luôn bật; Regenerate/Branch khóa khi `generating`.

### 6.4 Các mục khác
- **Cuộn khi gửi:** `followNewest` trong `ChatScreen`. Gửi tin → khi tin mới (và tin placeholder của AI) xuất hiện thì `animateScrollToItem(0)`. Tự tắt khi người dùng kéo list (`DragInteraction.Start`) hoặc khi AI xong. (Lý do dùng cờ thay vì cuộn ngay: lúc bấm gửi tin chưa có trong list.)
- **Top bar:** chấm nhỏ (Badge) trên nút ⋮ + dấu ✓ cạnh "System prompt" khi cuộc có system prompt (`systemPrompt.isNotBlank()`; lưu ý cuộc mới được sao chép prompt mặc định từ Settings nên nếu Settings có prompt thì mọi cuộc đều có chấm).
- **Vòng ngữ cảnh > 90%:** bấm vào vòng → hộp thoại có thêm nút **"New chat"** (gọi `onNewChat`, tạo cuộc **trống** mới; KHÔNG phải Branch, không mang theo tin nào). Chỉ ở trong hộp thoại, không có snackbar tự bật (tránh phiền mỗi lần mở cuộc dài).
- **Tìm trong cuộc:** phím Enter/Search trên bàn phím = kết quả kế tiếp (giữ bàn phím mở).
- **Toast "Copied"** chỉ hiện khi Android < 13.
- **Lỗi AI (`ui/ErrorHints.kt`, có test):** thẻ lỗi có "Retry" + nút ngữ cảnh. Quy tắc: chưa có nhà cung cấp / thiếu API key (trừ loại Custom) / địa chỉ không bắt đầu bằng http → **"Open provider settings"**; chưa chọn mô hình → **"Select model"** (mở bảng chọn mô hình); mã HTTP 401/403/404/429 → "Open provider settings". Các lỗi thiết lập quyết định theo **trạng thái hiện tại** (sửa xong thì nút biến mất). Mã HTTP đọc từ dòng chi tiết (`... 401: ...`, ở dòng thứ hai) bằng regex `\d{3}:` nên **không phụ thuộc ngôn ngữ** khi Đợt 3 Anh hóa `Http.kt`. "Open provider settings" mở màn sửa nhà cung cấp của cuộc (hoặc danh sách nhà cung cấp nếu chưa có).
- **Màn trống:** chỉ "Hi, Welcome" (đã bỏ 4 gợi ý). Vẫn giữ 2 thẻ "No AI connected yet" / "Choose a model".
- **Thẻ công cụ (`ui/ChatToolCards.kt`, `ui/ToolSummary.kt` mới, có test):** hiện câu dễ đọc theo UID (ví dụ "Read file notes/todo.md (lines 3–10)"), trạng thái rõ: *Running…* (vòng xoay) / *Waiting for your approval…* / *Done* / *Failed or denied*. Bấm để mở: tên công cụ, **JSON tham số được thụt dòng** và kết quả. UID lạ → hiện tên công cụ. Khi thêm công cụ mới ở sau, **thêm 1 dòng vào `toolSummary()`**.
- **Hộp xác nhận (`ui/ConfirmationHost.kt`):** Allow | **Allow all in this turn** | **Always allow this tool** | Deny. Với thao tác **xóa** chỉ còn Delete | Deny (không có lối tắt, đúng specs "xóa luôn hỏi").
  - "Allow all in this turn": `ConfirmRequest.turnId` (mới) + `ConfirmationBroker.allowAllInTurn()` / `endTurn()`. Duyệt luôn các yêu cầu đang chờ cùng lượt và các yêu cầu sau trong lượt đó (không bao giờ gồm Delete). `ChatEngine.start` gọi `tools.endTurn(turnId)` ở `finally` nên quyền hết hiệu lực khi AI xong/bị dừng.
  - "Always allow this tool": `registry.setMode(tool, ToolMode.AUTO)` → thành mặc định, hoàn tác được trong Cài đặt → Công cụ. Không hiện cho thao tác xóa.
  - `ToolRuntime.execute(call, turnId)` và `ToolRuntime.endTurn(turnId)` là chữ ký mới.

### 6.4b Anh hóa đã làm kèm (chỉ trong các file đã đụng vào)
`ChatScreen`, `ChatMessages`, `ChatInputBar`, `ChatToolCards`, `ConfirmationHost`, `ChatEmptyState`, `ContextRing`, `SelectTextSheet`, `Dialogs` (dùng chung nên đổi luôn: Save/Cancel/OK/Filter/Add selected/Close), cùng 2 chuỗi trong `ChatViewModel` ("Image", "Couldn't create the branch."). Phần còn lại vẫn tiếng Việt (xem mục 7).

### 6.5 Checklist test Đợt 2 (người dùng chạy thử)
> Lưu ý quan trọng: **chưa build thử** (xem mục 2). Nếu build lỗi, dán log để sửa.
1. **Nhấn giữ** vào tin của bạn *và* vào tin AI (kể cả giữa đoạn văn có chữ) → hiện bottom sheet. *(Kiểm tra đầu tiên: tin AI hiển thị bằng TextView của Markwon; nếu nhấn giữ trên chữ không mở được menu thì báo lại – có phương án dự phòng.)*
2. Không còn nút ⋮ ở tin nào. Menu đúng danh sách ở 6.2.
3. Khi AI đang trả lời: nhấn giữ vào tin → Copy và Select text dùng được, các mục còn lại mờ + có dòng giải thích. Copy tin đang viết ra đúng phần chữ đang hiện.
4. Chỉ **câu trả lời cuối cùng** có hàng Copy / Regenerate / Branch; các câu cũ không có hàng nút.
5. Cuộn lên đọc tin cũ → gõ và gửi → danh sách tự xuống tin mới và bám theo câu trả lời; đang bám mà vuốt tay lên thì ngừng bám.
6. Ô nhập: placeholder "Ask anything"; "+", tên mô hình, nút gửi cùng một hàng dưới khung nhập. Thêm ảnh → nút X bấm dễ (vùng chạm to).
7. Android 13+: copy không hiện toast "Copied" (hệ thống tự báo); Android thấp hơn có toast.
8. Tìm trong cuộc: gõ từ khóa, bấm Enter trên bàn phím → nhảy kết quả kế tiếp.
9. Cuộc có system prompt → chấm trên nút ⋮ + dấu ✓ ở mục "System prompt". Cuộc dùng >90% bộ nhớ → bấm vòng thấy nút "New chat".
10. Gây lỗi: xóa API key rồi gửi → thẻ lỗi có "Retry" + "Open provider settings"; bỏ chọn mô hình → "Select model".
11. Màn trống chỉ có "Hi, Welcome".
12. Bật "Hỏi trước" cho công cụ ghi file, nhờ AI sửa nhiều file: thẻ công cụ hiện câu dễ đọc + "Waiting for your approval…"; bấm **Allow all in this turn** → các file còn lại không hỏi nữa; sang câu hỏi mới thì hỏi lại. **Always allow this tool** → công cụ đó chuyển sang Tự động (xem trong màn Công cụ). Thử công cụ xóa: chỉ có Delete / Deny.

### 6.6 Điều cần người dùng xác nhận (đang giả định)
- Tên mô hình trong ô nhập: giữ tiền tố nhà cung cấp khi có ≥ 2 nhà cung cấp (khảo sát chỉ nói "giữ chuỗi ID thô"). Muốn chỉ ID thuần thì sửa `chipLabel` trong `ChatScreen.kt`.
- Giữ mục "Select text" trong menu (khảo sát: Chọn chữ phải luôn dùng được).
- Nút "New chat" trong hộp thoại vòng ngữ cảnh = mở chat **trống**. (Đã đổi nhãn từ "Start a new chat from here" vì "from here" dễ bị hiểu là Branch.) Nếu muốn nút này mang theo một phần ngữ cảnh (ví dụ tóm tắt) thì cần bàn thêm.
- Quy ước Branch (người dùng đã xác nhận, code `ChatOps.branchAt` đang đúng): Branch ở lượt N → cuộc mới giữ lượt 1..N, bỏ các lượt sau; cuộc gốc giữ nguyên.

### 6.7 Danh sách file thay đổi trong Đợt 2
Mới: `ui/MessageMenu.kt`, `ui/ErrorHints.kt`, `ui/ToolSummary.kt`, test `ErrorHintsTest`, `ToolSummaryTest` (thư mục `ui`), `ConfirmationBrokerTest` (thư mục `tools`).
Viết lại: `ui/ChatInputBar.kt`, `ui/ChatMessages.kt`, `ui/ChatToolCards.kt`, `ui/ConfirmationHost.kt`, `ui/ChatEmptyState.kt`, `ui/ContextRing.kt`.
Sửa: `ui/ChatScreen.kt` (tham số mới `onOpenProvider`, `onNewChat`), `ui/ChatHost.kt`, `ui/AppNav.kt`, `ui/ChatViewModel.kt`, `ui/Dialogs.kt`, `ui/SelectTextSheet.kt`, `tools/Brokers.kt`, `tools/ToolRegistry.kt`, `chat/ToolRuntime.kt`, `chat/ChatEngine.kt`.

## 7. Đợt 3 – đã làm gì (danh sách / cài đặt / công cụ + Anh hóa)

### 7.1 Anh hóa toàn app
- Đã chuyển sang tiếng Anh: **toàn bộ giao diện**, **tên/mô tả mặc định và tham số của mọi công cụ gửi cho AI**, kết quả/lỗi công cụ, lỗi HTTP (`ai/Http.kt`), lỗi của `ChatEngine`, thông báo + tên kênh thông báo, `ThemeMode.label`, `ToolMode.label`, `ToolGroup.label`, `ProviderType.CUSTOM.label`, câu "Interrupted ..." khi mở lại app mà AI đang dở (SQL trong `Daos.kt`), tên thứ trong tuần của công cụ ngày giờ (Monday...).
- Chỗ **cố ý còn tiếng Việt** (đừng "sửa"): `util/Text.kt` (`'đ'` trong hàm bỏ dấu để tìm không phân biệt dấu), `ChatOps.kt` (`removeSuffix(" (nhánh)")` để nhận ra tên nhánh cũ), `Entities.kt` (`LEGACY_DEFAULT_TITLE`), chú thích code, và dữ liệu cũ đã lưu trong máy (tin nhắn/kết quả công cụ cũ vẫn là tiếng Việt).
- **Tiêu đề mặc định:** `DEFAULT_TITLE = "New chat"`. `isDefaultTitle()` nhận cả tiêu đề cũ "Cuộc trò chuyện mới" (để vẫn tự đặt tên theo tin nhắn đầu). `displayTitle()` hiển thị tiêu đề cũ thành "New chat" (Drawer, top bar, hộp đổi tên, thông báo, tìm kiếm). Dữ liệu trong DB **không** bị sửa (không cần migration).
- `HttpErrorTest` đã cập nhật theo chuỗi mới ("Details: Error 401: ..."). `ErrorHints` vẫn đọc mã HTTP bằng regex `\d{3}:` nên không bị ảnh hưởng.

### 7.2 Drawer (`ui/ConversationDrawer.kt`, viết lại)
- **Dòng phụ = xem trước tin cuối** (tin user/AI mới nhất có chữ, cắt 160 ký tự trong SQL: `MessageDao.observeLastMessages()`). Chỉ theo dõi khi Drawer đang mở (`isOpen`), tránh truy vấn lại mỗi giây lúc AI đang stream.
- **Nhóm theo ngày:** Pinned / Today / Yesterday / Previous 7 days / Older (`ui/ConversationGroups.kt`, theo *ngày lịch*, có test). Nhóm rỗng bị ẩn.
- **Ghim:** menu ⋮ hoặc nhấn giữ → Pin/Unpin (cột `pinned` có từ Đợt 1, `ConversationDao.setPinned`). Cuộc ghim nằm nhóm "Pinned" đầu tiên.
- **Xóa có Hoàn tác (Undo):** vuốt **sang phải** hoặc Delete trong menu → cuộc bị *ẩn ngay*, snackbar "Conversation deleted · Undo" (4 giây). Hết giờ/đóng snackbar mới xóa thật (kể cả ảnh đính kèm); bấm Undo thì hiện lại. Xóa cuộc thứ hai sẽ chốt xóa cuộc trước ngay. Không còn hộp xác nhận xóa cuộc. (Vì hết hạn Undo mới xóa thật, nếu app bị tắt đột ngột trong 4 giây đó thì cuộc hiện lại – không mất dữ liệu.)
  - Chọn vuốt **phải** (không phải trái) vì vuốt trái trên Drawer là cử chỉ đóng Drawer, dễ xóa nhầm.
  - Xóa đúng cuộc đang mở → app mở một chat mới (`onDeleted` → `onNewChat`); Undo chỉ đưa cuộc đó về danh sách, không tự mở lại.
- **Nhấn giữ** một dòng → menu Rename / Pin / Select / Delete (có rung nhẹ). Nút ⋮ vẫn giữ để dễ thấy.
- **Chọn nhiều:** menu → Select vào chế độ chọn (checkbox, đếm "N selected", Select all, Delete). Back thoát chế độ chọn; đóng Drawer cũng thoát. Xóa hàng loạt cũng có Undo. Trong chế độ chọn tắt vuốt xóa.
- **Tìm kiếm ngay trong Drawer** (ô "Search chats"): thay màn Search riêng (đã **xóa** `SearchScreen.kt` và route `search`). Kết quả **gom theo cuộc**: tên cuộc + tối đa 3 đoạn trích tô sáng ("You:"/"AI:") + "+N more". Bấm tên cuộc/đoạn trích → mở đúng cuộc, cuộn tới tin đó, mở thanh tìm trong cuộc với từ khóa. Cuộc chỉ khớp tên → mở thẳng (không mở thanh tìm). Logic thuần ở `ui/ConversationSearch.kt` (`buildSearchGroups`, có test). Vẫn bỏ dấu tiếng Việt (`TextUtil.findAll`). Đang gõ thì hiện vòng xoay, tối đa 50 cuộc.
  - Hiệu năng: vẫn nạp mọi tin nhắn vào bộ nhớ mỗi lần tìm (debounce 250ms, chạy luồng nền). FTS để Đợt 4.
- Mở kết quả tìm trong **cuộc đang hiển thị** vẫn làm mới được: `AppNav.openNonce` tăng mỗi lần `openChat()`, `ChatHost` dùng `key(conversationId, openNonce)`.

### 7.3 Cài đặt (`ui/SettingsScreen.kt`, viết lại)
- Chia 4 nhóm có tiêu đề: **AI** (providers, default system prompt) · **Tools** (Manage tools, Web search: chọn Brave/Tavily bằng nút 3 đoạn + API key) · **Data & workspace** (thư mục, Notes & to-dos) · **Appearance** (theme bằng nút chọn ngang System/Light/Dark).
- **Workspace** hiện tên thư mục dạng tương đối, ví dụ `./homework` (`WorkspaceFs.displayPath()`: lấy tên từ DocumentFile, nếu null thì lấy từ id của cây thư mục; đọc ở luồng nền). Thư mục gốc của ổ lưu trữ hiện `./`.
- Nút "Kiểm tra" → **"Check read/write access"**.

### 7.4 Quản lý công cụ (`ui/ToolsScreen.kt`, viết lại)
- Danh sách: dòng phụ là **mô tả ngắn** (câu đầu của mô tả, `shortDescription()`, có test) thay cho UID; thêm nhãn nhỏ "renamed" / "blocked: ...". Có **ô tìm** (khớp tên, mô tả, UID).
- **Theo nhóm:** nút "Set all" ở đầu mỗi nhóm: Reset to defaults / All Auto (công cụ xóa vẫn Hỏi) / All Ask first / All Off.
- **Màn chi tiết:** công tắc Bật + chip trùng nhau đã gộp thành **một điều khiển 3 đoạn Auto | Ask first | Off** (công cụ xóa chỉ có Ask first | Off). UID vẫn nằm ở màn chi tiết.
- **Bảo vệ chỉnh sửa dở:** nếu tên/mô tả chưa lưu mà bấm Back (nút hệ thống hoặc mũi tên) → hộp "Discard changes?" (Discard / Cancel).

### 7.5 Checklist test Đợt 3 (người dùng chạy thử)
> Vẫn **chưa build thử** (xem mục 2). Nếu build lỗi, dán log để sửa.
1. Mở app: mọi chữ trong giao diện là tiếng Anh. Nhờ AI dùng công cụ (ví dụ đọc file): thẻ công cụ, hộp xác nhận, kết quả đều tiếng Anh. Cuộc cũ tên "Cuộc trò chuyện mới" hiện là "New chat".
2. Mở Drawer: mỗi cuộc có dòng xem trước tin cuối; có các nhóm Today / Yesterday / Previous 7 days / Older.
3. Nhấn giữ một cuộc → menu; Pin → cuộc nhảy lên nhóm Pinned; Unpin ngược lại.
4. Vuốt một cuộc sang phải → biến mất + snackbar Undo; bấm Undo → hiện lại; để hết giờ → mất hẳn. Thử xóa đúng cuộc đang mở → app sang chat mới.
5. Menu → Select → chọn vài cuộc → Delete → snackbar "N conversations deleted" + Undo. Back thoát chế độ chọn.
6. Gõ vào ô "Search chats": kết quả gom theo cuộc; bấm một đoạn trích → mở đúng cuộc, cuộn tới tin đó, thanh tìm trong cuộc có từ khóa. Thử tìm không dấu (ví dụ "dieu" ra "điều") và thử bấm kết quả thuộc cuộc đang mở.
7. Cài đặt: 4 nhóm rõ ràng; chọn thư mục workspace → hiện `./tên-thư-mục`; nút "Check read/write access" cho ra thông báo; theme đổi bằng nút 3 đoạn.
8. Quản lý công cụ: gõ vào ô tìm; "Set all" ở một nhóm; vào chi tiết một công cụ → đổi mức bằng nút 3 đoạn; sửa tên rồi bấm Back → có hộp "Discard changes?".
9. Cập nhật từ bản Đợt 2: dữ liệu cũ vẫn còn, DB vẫn version 2 (không đổi cấu trúc DB ở Đợt 3).

### 7.6 Điều cần người dùng xác nhận (đang giả định)
- Vuốt **phải** để xóa cuộc (vuốt trái dành cho đóng Drawer). Nếu muốn vuốt trái thì sửa `enableDismissFromStartToEnd/EndToStart` trong `ConversationRow`.
- Xóa cuộc không còn hộp xác nhận (đã có Undo 4 giây).
- Màn Search riêng đã bỏ hẳn, chỉ còn tìm trong Drawer.
- Thông báo lỗi gửi cho AI và lịch sử cũ: tin nhắn/kết quả công cụ đã lưu từ trước vẫn là tiếng Việt (không sửa dữ liệu cũ).

### 7.7 Danh sách file thay đổi trong Đợt 3
Mới: `ui/ConversationGroups.kt`, `ui/ConversationSearch.kt`, test `ConversationGroupsTest`, `ConversationSearchTest`, `ShortDescriptionTest` (thư mục `ui`).
Xóa: `ui/SearchScreen.kt`.
Viết lại: `ui/ConversationDrawer.kt`, `ui/SettingsScreen.kt`, `ui/ToolsScreen.kt`.
Sửa (nối Drawer/tìm kiếm): `ui/ChatHost.kt` (tham số `openNonce`, `onOpenSearchResult`; bỏ `onSearch`), `ui/AppNav.kt` (bỏ route `search`, thêm `openNonce`), `data/Daos.kt` (`setPinned`, `observeLastMessages`, `LastMessageRow`), `data/Entities.kt`, `ui/ChatViewModel.kt`, `ui/ChatScreen.kt`, `chat/ChatEngine.kt` (dùng `displayTitle`), `tools/WorkspaceFs.kt` (`displayPath()`).
Anh hóa (chỉ đổi chuỗi): `tools/{FsTools,TextTools,DataTools,DailyTools,ToolCore,ToolRegistry,WorkspaceFs}.kt`, `ai/{Http,ClaudeProvider,GeminiProvider}.kt`, `chat/{ChatEngine,ChatNotifier,ChatOps,GenerationService}.kt`, `data/{Daos,ProviderConfig,SettingsRepository}.kt`, `util/Text.kt`, `App.kt`, `ui/{CodeBlock,ImageViewerDialog,ModelPickerSheet,NotesScreen,OnboardingScreen,ProviderEditScreen,ProvidersScreen}.kt`, test `ai/HttpErrorTest.kt`.

## 8. Kế hoạch Đợt 4 – P2 (CHƯA LÀM)
- Bảng màu/ font / bo góc riêng + fallback cho máy cũ (Dynamic Color vẫn dùng khi có).
- Chỉ báo "Thinking" + ba chấm động; hiệu ứng xuất hiện nhẹ cho tin mới; haptic nhẹ khi gửi/dừng.
- Tìm kiếm toàn cục bằng FTS của Room (vẫn bỏ dấu tiếng Việt như hiện có) – cần migration DB v3.

## 9. Ghi chú kỹ thuật cần nhớ
- Room `version = 2` (Đợt 3 **không** đổi cấu trúc DB), `exportSchema = false`, chỉ có `MIGRATION_1_2` (không dùng destructive migration). Thêm cột/bảng mới → viết migration mới.
- `ConversationRepository.create()` luôn dọn cuộc rỗng; đừng gọi nó "dự phòng" khi người dùng đang đứng trong một cuộc rỗng khác.
- `ChatScreen` được bọc `key(conversationId)` trong `ChatHost` và `viewModel(key = conversationId)` – mỗi cuộc có ViewModel riêng.
- Nếu lỗi build ở Đợt 1, các chỗ dễ sai nhất: `ChatHost.kt` (API Drawer của Material3 1.3.x), `ChatNotifier.kt` (import `Risk`, `ConfirmRequest`), `ConversationDrawer.kt` (`ListItemDefaults.colors`), `ChatScreen.kt` (tham số mới), `AppDatabase.kt` (migration).
- (Đợt 2) `ToolRuntime.execute(call, turnId)` + `endTurn(turnId)`; `ConfirmRequest` có thêm `turnId`. Nếu thêm nơi nào gọi `execute`, phải truyền `turnId` (nếu không, "Allow all in this turn" không hoạt động cho lời gọi đó).
- (Đợt 3) Khi thêm công cụ mới: mô tả/tham số viết **tiếng Anh**; thêm 1 dòng vào `toolSummary()`; mô tả nên mở đầu bằng một câu ngắn vì `shortDescription()` lấy câu đầu để hiện trong danh sách.
- (Đợt 3) Khi thêm chuỗi giao diện mới: dùng tiếng Anh. Tên cuộc trò chuyện lấy từ DB phải đi qua `displayTitle()` trước khi hiển thị.
- (Đợt 3) Xóa cuộc trò chuyện luôn đi qua `ConversationListViewModel.hide()` → `commitDelete()` (có Undo). Đừng gọi `ConversationRepository.delete()` trực tiếp từ giao diện, trừ khi cố ý bỏ Undo.
- (Đợt 3) Biến/tham số tên `open` dễ bị nhầm với từ khóa; ở code mới dùng `isOpen`.
- (Đợt 3) Nếu lỗi build ở Đợt 3, các chỗ dễ sai nhất: `ConversationDrawer.kt` (`SwipeToDismissBox`/`rememberSwipeToDismissBoxState` và `Modifier.animateItem()` của Material3 1.3.x / Foundation 1.7.x; `combinedClickable`), `SettingsScreen.kt` và `ToolsScreen.kt` (`SingleChoiceSegmentedButtonRow`/`SegmentedButton`), `Daos.kt` (truy vấn `observeLastMessages`).
- (Đợt 2) `ErrorHints.kt` đọc mã HTTP ở **dòng thứ hai** của chuỗi lỗi (định dạng "câu thân thiện\nChi tiết: Lỗi 401: ..."). Đợt 3 đã Anh hóa `Http.kt` giữ nguyên cấu trúc hai dòng: "<câu thân thiện>\nDetails: Error <mã>: ..." và `HttpErrorTest` đã khớp. `ErrorHintsTest` đã dùng chuỗi tiếng Anh nên không bị ảnh hưởng.
- (Đợt 2) Nếu lỗi build ở Đợt 2, các chỗ dễ sai nhất: `ChatMessages.kt` (hai overload `combinedClickable`: có/không `interactionSource` + `indication`), `ChatInputBar.kt` (`surfaceContainerHigh`, Material3 1.3.x), `ContextRing.kt` (`dismissButton = if (...) { { ... } } else null`), `ChatScreen.kt` (`BadgedBox`, `listState.interactionSource`), `ConfirmationBrokerTest.kt` (`runBlocking`).
