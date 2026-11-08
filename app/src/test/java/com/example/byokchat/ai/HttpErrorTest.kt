package com.example.byokchat.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiểm tra câu báo lỗi thân thiện: mỗi mã có câu riêng và vẫn giữ chi tiết gốc. */
class HttpErrorTest {

    private fun body(msg: String) = "{\"error\":{\"message\":\"$msg\"}}"

    private fun check(code: Int, friendly: String) {
        val r = errorMessage(code, body("chi tiet goc"))
        assertTrue("mã $code thiếu câu thân thiện: $r", r.startsWith(friendly + "\n"))
        assertTrue("mã $code thiếu chi tiết gốc: $r", r.contains("Chi tiết: Lỗi $code: chi tiet goc"))
    }

    @Test fun code400() = check(400, "Yêu cầu bị từ chối vì nội dung hoặc cài đặt chưa hợp lệ.")

    @Test fun code401() = check(401, "API key không đúng hoặc đã bị thu hồi. Hãy kiểm tra lại key trong Cài đặt → Nhà cung cấp AI.")

    @Test fun code403() = check(403, "Key này không có quyền dùng mô hình hoặc chức năng đang chọn, hoặc tài khoản bị hạn chế.")

    @Test fun code404() = check(404, "Không tìm thấy mô hình hoặc địa chỉ máy chủ. Hãy kiểm tra tên mô hình và địa chỉ máy chủ.")

    @Test fun code408() = check(408, "Máy chủ phản hồi quá lâu. Hãy thử lại.")

    @Test fun code413() = check(413, "Nội dung gửi đi quá lớn (cuộc trò chuyện quá dài hoặc ảnh quá lớn).")

    @Test fun code429() = check(429, "Bạn gửi quá nhanh hoặc đã hết hạn mức của tài khoản. Hãy đợi một lúc rồi thử lại, hoặc kiểm tra hạn mức/số dư.")

    @Test fun serverErrors500to599() {
        val friendly = "Máy chủ của nhà cung cấp đang gặp sự cố hoặc quá tải. Hãy thử lại sau ít phút."
        for (code in listOf(500, 502, 503, 529, 599)) check(code, friendly)
    }

    @Test fun otherCodes() {
        check(418, "Nhà cung cấp trả về lỗi.")
        check(499, "Nhà cung cấp trả về lỗi.")
        check(600, "Nhà cung cấp trả về lỗi.")
    }

    @Test fun errorAsPlainStringIsUsedAsDetail() {
        val r = errorMessage(401, "{\"error\":\"bad key\"}")
        assertTrue(r.endsWith("Chi tiết: Lỗi 401: bad key"))
    }

    @Test fun bodyIsNotJson() {
        val r = errorMessage(502, "<html>Bad Gateway</html>")
        assertTrue(r.contains("Chi tiết: Lỗi 502: <html>Bad Gateway</html>"))
    }

    @Test fun emptyBody() {
        val r = errorMessage(500, "")
        assertTrue(r.endsWith("Chi tiết: Lỗi 500: không rõ nguyên nhân"))
    }

    @Test fun longBodyIsCutTo300Chars() {
        val r = errorMessage(500, "x".repeat(1000))
        assertEquals(300, r.substringAfter("Lỗi 500: ").length)
    }

    @Test fun networkMessage() {
        val r = networkErrorMessage("timeout")
        assertEquals("Không kết nối được mạng. Hãy kiểm tra Wi-Fi/4G rồi thử lại.\nChi tiết: timeout", r)
    }
}
