package com.weike.ime.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateClientTest {
    private val client = UpdateClient()

    private val validManifest = """
        {
          "versionCode": 34,
          "versionName": "1.5.4",
          "apkUrl": "https://bucket.oss-cn-beijing.aliyuncs.com/updates/lianai-1.5.4.apk",
          "sha256": "b870605c963d7c8caa05ed9e2c5e095475014d98dbeccd2cb4782e3659463447",
          "sizeBytes": 58490200,
          "notes": "修复若干问题"
        }
    """.trimIndent()

    @Test
    fun manifestParsesIntoUpdateInfo() {
        val info = client.parseUpdateInfo(validManifest)
        assertEquals(34L, info.versionCode)
        assertEquals("1.5.4", info.versionName)
        assertEquals("修复若干问题", info.notes)
    }

    @Test
    fun downloadFieldsAreOptional() {
        // 浏览器下载方案：apkUrl/sha256/sizeBytes 不再参与客户端逻辑，缺省也能解析。
        val info = client.parseUpdateInfo("""{"versionCode":37,"versionName":"1.5.7","notes":"x"}""")
        assertEquals(37L, info.versionCode)
        assertEquals("1.5.7", info.versionName)
    }

    @Test
    fun manifestWithoutVersionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            client.parseUpdateInfo("""{"apkUrl":"https://example.com/a.apk","sha256":"${"a".repeat(64)}"}""")
        }
    }
}
