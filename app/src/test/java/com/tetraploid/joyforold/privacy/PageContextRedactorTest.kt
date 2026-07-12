package com.tetraploid.joyforold.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageContextRedactorTest {
    @Test
    fun redacts_phone_and_otp() {
        val out = PageContextRedactor.redact("联系人 13812345678 验证码 884422")
        assertFalse(out.contains("13812345678"))
        assertFalse(out.contains("884422"))
        assertTrue(out.contains("[手机号]"))
        assertTrue(out.contains("[验证码]"))
    }

    @Test
    fun redactForLog_keepsOperationalNumbers() {
        val out = PageContextRedactor.redactForLog(
            "唤醒监听统计：frames=1004, vadPass=884, hits=0\n" +
                "...（工具详情已截断，共 1234 字）",
        )
        assertTrue(out.contains("frames=1004"))
        assertTrue(out.contains("共 1234 字"))
        assertFalse(out.contains("[验证码]"))
    }
}
