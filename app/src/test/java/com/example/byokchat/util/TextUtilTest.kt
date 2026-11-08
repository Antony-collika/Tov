package com.example.byokchat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Kiểm tra TextUtil.findAll (tìm không phân biệt hoa/thường và dấu tiếng Việt).
 * Chữ có dấu được viết bằng mã \u để không phụ thuộc cách gõ dấu của trình soạn thảo:
 *   \u0110 = Đ, \u0111 = đ, \u1EC1 = ề, \u1EC7 = ệ, \u01B0 = ư, \u1EDD = ờ
 */
class TextUtilTest {

    @Test
    fun ignoresUpperAndLowerCase() {
        assertEquals(listOf(0, 6, 12), TextUtil.findAll("Hello hello HELLO", "hello"))
        assertEquals(listOf(0, 6, 12), TextUtil.findAll("Hello hello HELLO", "HeLLo"))
    }

    @Test
    fun ignoresVietnameseAccents() {
        // "Điều kiện" tìm bằng "dieu" khớp ở vị trí 0
        assertEquals(listOf(0), TextUtil.findAll("\u0110i\u1EC1u ki\u1EC7n", "dieu"))
        // "Điều kiện" tìm bằng "kien" khớp ở vị trí 5
        assertEquals(listOf(5), TextUtil.findAll("\u0110i\u1EC1u ki\u1EC7n", "kien"))
        // chiều ngược lại: tìm có dấu, chữ gốc không dấu
        assertEquals(listOf(0), TextUtil.findAll("Dieu", "\u0111i\u1EC1u"))
    }

    @Test
    fun letterDWithStrokeMatchesPlainD() {
        assertEquals(listOf(0), TextUtil.findAll("\u0110", "d"))
        assertEquals(listOf(0), TextUtil.findAll("\u0111", "D"))
        assertEquals(listOf(0), TextUtil.findAll("d", "\u0111"))
        assertEquals(listOf(0), TextUtil.findAll("\u0111\u01B0\u1EDDng", "duong"))
    }

    @Test
    fun emptyQueryReturnsEmptyList() {
        assertEquals(emptyList<Int>(), TextUtil.findAll("abc", ""))
        assertEquals(emptyList<Int>(), TextUtil.findAll("", ""))
    }

    @Test
    fun noMatchReturnsEmptyList() {
        assertEquals(emptyList<Int>(), TextUtil.findAll("abc", "xyz"))
        assertEquals(emptyList<Int>(), TextUtil.findAll("", "abc"))
    }

    @Test
    fun twoMatchesReturnTwoPositions() {
        assertEquals(listOf(0, 8), TextUtil.findAll("abc xyz abc", "abc"))
    }

    @Test
    fun matchesDoNotOverlap() {
        assertEquals(listOf(0, 2), TextUtil.findAll("aaaa", "aa"))
    }
}
