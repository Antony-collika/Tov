# ĐỀ XUẤT: ĐỔI KHÓA KÝ ỨNG DỤNG

> Tài liệu này chỉ là **đề xuất**. Chưa có dòng code nào về khóa ký bị thay đổi. Agent chỉ làm tiếp khi bạn trả lời đồng ý bằng văn bản (xem mục 5).

## 1. Vấn đề, nói bằng lời thường

Mỗi bản app Android được "đóng dấu" bằng một **khóa ký**. Điện thoại chỉ cho cập nhật đè khi bản mới có cùng dấu với bản cũ. Ai giữ được khóa thì ký ra được một bản app giả mà điện thoại của bạn tin là bản cập nhật thật.

Hiện tại:

- Thư mục `signing/` trong dự án chứa **chính file khóa** (`tov-development.p12.base64`).
- File `app/build.gradle.kts` chứa **mật khẩu của khóa** ngay trong code (viết dưới dạng ghép hai đoạn chữ, nhưng ai đọc cũng ghép lại được).

Nếu kho mã này là **công khai** (hoặc từng công khai), người lạ có thể lấy khóa và mật khẩu. Kể cả khi bạn xóa file đi, khóa vẫn nằm trong **lịch sử** của kho mã, nên phải coi khóa này **đã lộ vĩnh viễn**.

Mức độ rủi ro thực tế: người lạ có thể phát tán bản "Tov" giả, được ký đúng khóa, và điện thoại sẽ cho nó cài đè lên bản thật. Bản giả đó có thể đọc API key và cuộc trò chuyện của bạn.

## 2. Cái giá của việc đổi khóa (cần biết trước)

Khóa mới nghĩa là "dấu" mới. Điện thoại sẽ **từ chối** cập nhật đè bản cũ. Bạn phải:

1. Gỡ app cũ khỏi điện thoại,
2. Cài bản mới,
3. Nhập lại API key và cấu hình.

Khi gỡ app, **dữ liệu trên máy (cuộc trò chuyện, ghi chú, cấu hình) sẽ mất**, trừ khi đã sao lưu trước.

**Lưu ý quan trọng:** tính năng xuất/nhập sao lưu (được nhắc là T4.7 trong kế hoạch) **hiện chưa có trong app** và cũng chưa có trong phần kế hoạch agent đã nhận. Vì vậy, nếu đổi khóa ngay bây giờ thì **không có cách sao lưu**, và dữ liệu cũ sẽ mất. Đây là lý do mình đề xuất **chưa đổi vội**.

## 3. Đề xuất

**Lưu khóa mới ở GitHub Secrets**, không để trong kho mã. Dùng 4 bí mật tên chính xác như sau:

| Tên secret | Nội dung |
|---|---|
| `TOV_KEYSTORE_BASE64` | File khóa đã mã hóa thành một dòng chữ dài (base64) |
| `TOV_KEYSTORE_PASSWORD` | Mật khẩu của file khóa |
| `TOV_KEY_ALIAS` | Tên khóa bên trong file (đề xuất: `tov`) |
| `TOV_KEY_PASSWORD` | Mật khẩu của khóa |

Cách hoạt động sau khi đổi:

- Gradle đọc 4 giá trị trên từ **biến môi trường** (khi chạy trên GitHub) hoặc từ file `local.properties` (khi build trên máy bạn; file này không đưa vào kho mã).
- Quy trình CI (GitHub Actions) ghi file khóa ra từ secret `TOV_KEYSTORE_BASE64` ngay lúc build.
- Nếu **không có** secret (ví dụ ai đó fork kho mã về build thử): build dùng **khóa debug** mặc định của Android, vẫn ra được APK nhưng không thể cập nhật đè bản chính thức.
- Xóa thư mục `signing/` và phần mật khẩu trong `app/build.gradle.kts`.

## 4. Hướng dẫn từng bước (cho người không chuyên)

Chỉ làm khi bạn đã quyết định đổi khóa (mục 5). Cần máy tính có cài Java (lệnh `keytool` đi kèm Java).

**Bước 1 — Tạo khóa mới.** Mở cửa sổ dòng lệnh và chạy:

```
keytool -genkeypair -v -keystore tov-release.p12 -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias tov
```

Máy sẽ hỏi mật khẩu (hãy đặt mật khẩu dài, khác mật khẩu cũ, và **lưu vào trình quản lý mật khẩu**) và vài thông tin tên/tổ chức (điền gì cũng được). Kết quả là file `tov-release.p12`.

**Bước 2 — Cất file khóa ở nơi an toàn.** Sao lưu `tov-release.p12` vào nơi riêng tư của bạn (ổ USB, trình quản lý mật khẩu…). **Mất file này = không bao giờ cập nhật đè được app nữa.** Tuyệt đối **không** đưa file này vào kho mã.

**Bước 3 — Mã hóa thành một dòng chữ (base64).**

- Linux: `base64 -w 0 tov-release.p12`
- macOS: `base64 -i tov-release.p12`
- Windows (PowerShell): `[Convert]::ToBase64String([IO.File]::ReadAllBytes("tov-release.p12"))`

Sao chép toàn bộ dòng chữ kết quả.

**Bước 4 — Dán vào GitHub.** Vào trang kho mã trên GitHub → Settings → Secrets and variables → Actions → New repository secret. Tạo đủ 4 secret ở mục 3 (dán dòng chữ ở bước 3 vào `TOV_KEYSTORE_BASE64`).

**Bước 5 — Báo lại cho agent** rằng đã đặt đủ 4 secret, để agent sửa code ký và quy trình CI.

## 5. Trình tự đề xuất và cách trả lời

Trình tự an toàn nhất:

1. Làm xong tính năng **xuất sao lưu** trong app (T4.7), rồi xuất sao lưu dữ liệu.
2. Đổi khóa (các bước ở mục 4, rồi agent sửa code).
3. Gỡ bản cũ, cài bản mới.
4. Nhập lại sao lưu.

**Để agent thực hiện thay đổi code ký, bạn cần trả lời bằng văn bản:** "Đồng ý đổi khóa ký" **và** "Đã đặt đủ 4 secret trên GitHub". Khi chưa có cả hai câu này, agent không đụng vào khóa ký.

**Nếu kho mã của bạn là riêng tư và chưa ai khác từng xem được**, rủi ro thấp hơn; bạn có thể chọn hoãn đến sau khi có tính năng sao lưu. Việc kho mã công khai hay riêng tư chỉ bạn biết, agent không kiểm tra được.
