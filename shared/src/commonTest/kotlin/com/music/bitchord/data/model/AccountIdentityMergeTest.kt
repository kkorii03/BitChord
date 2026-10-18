package com.music.bitchord.data.model

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountIdentityMergeTest {

    private val stored = Account(
        name = "김민수",
        email = "minsu@example.com",
        thumbnailUrl = "https://yt3.example/stored",
        handle = "@minsu",
    )

    @Test
    fun `blank new fields keep the stored identity`() {
        val merged = mergeAccountIdentity(stored, Account(name = "", email = "", thumbnailUrl = null))

        assertEquals(stored, merged)
    }

    @Test
    fun `whitespace and an empty avatar are silence too`() {
        val merged = mergeAccountIdentity(
            stored,
            Account(name = "   ", email = "\t", thumbnailUrl = "", handle = " "),
        )

        assertEquals(stored, merged)
    }

    @Test
    fun `the generic name never replaces a real name`() {
        val merged = mergeAccountIdentity(
            stored,
            Account(name = GENERIC_ACCOUNT_NAME, email = "", thumbnailUrl = null),
        )

        assertEquals("김민수", merged.name)
    }

    @Test
    fun `a real name still upgrades the generic name`() {
        val generic = Account(name = GENERIC_ACCOUNT_NAME, email = "", thumbnailUrl = null)

        val merged = mergeAccountIdentity(
            generic,
            Account(name = "Ada Lovelace", email = "ada@example.com", thumbnailUrl = null),
        )

        assertEquals("Ada Lovelace", merged.name)
        assertEquals("ada@example.com", merged.email)
    }

    @Test
    fun `a complete new identity wins`() {
        val fresh = Account(
            name = "Ada Lovelace",
            email = "ada@example.com",
            thumbnailUrl = "https://yt3.example/fresh",
            handle = "@ada",
        )

        assertEquals(fresh, mergeAccountIdentity(stored, fresh))
    }
}
