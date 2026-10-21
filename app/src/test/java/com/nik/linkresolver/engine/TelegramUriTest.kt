package com.nik.linkresolver.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramUriTest {
    @Test fun botStart() = assertEquals("tg://resolve?domain=FileBot&start=abc_123", TelegramUri.toApp("https://telegram.me/FileBot?start=abc_123"))
    @Test fun channel() = assertEquals("tg://resolve?domain=somechannel", TelegramUri.toApp("https://t.me/somechannel"))
    @Test fun post() = assertEquals("tg://resolve?domain=somechannel&post=42", TelegramUri.toApp("https://t.me/somechannel/42"))
    @Test fun invite() = assertEquals("tg://join?invite=AbCdEf", TelegramUri.toApp("https://t.me/+AbCdEf"))
    @Test fun joinchat() = assertEquals("tg://join?invite=XyZ", TelegramUri.toApp("https://t.me/joinchat/XyZ"))
    @Test fun notTelegram() = assertNull(TelegramUri.toApp("https://example.com/x"))
    @Test fun privatePostLeftAlone() = assertNull(TelegramUri.toApp("https://t.me/c/123/45"))
}
