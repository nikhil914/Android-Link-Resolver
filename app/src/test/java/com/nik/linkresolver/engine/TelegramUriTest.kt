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

    @Test fun botStartParsed() = assertEquals("FileBot" to "abc_123", TelegramUri.botStart("https://telegram.me/FileBot?start=abc_123"))
    @Test fun channelIsNotBotStart() = assertNull(TelegramUri.botStart("https://t.me/somechannel"))
    @Test fun inviteDetected() = org.junit.Assert.assertTrue(TelegramUri.isInvite("https://t.me/+AbC") && TelegramUri.isInvite("https://t.me/joinchat/x"))
    @Test fun channelIsNotInvite() = org.junit.Assert.assertFalse(TelegramUri.isInvite("https://t.me/somechannel"))
}
