package com.music.bitchord.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The account header youtube.com answers with, which differs from Music's in
 * the text shape rather than in the field names: `accountName` and
 * `channelHandle` can be `simpleText` where Music sends `runs`.
 */
class AccountMenuParserTest {

    private fun parse(json: String) =
        InnertubeParser.parseAccount(Json.parseToJsonElement(json))

    @Test
    fun `youtube menu reads simpleText name handle and photo`() {
        val account = parse(YOUTUBE_MENU)

        assertEquals("김민수", account?.name)
        assertEquals("@minsu", account?.email)
        assertEquals("@minsu", account?.handle)
        assertEquals("https://yt3.example/s88", account?.thumbnailUrl)
    }

    @Test
    fun `music menu keeps email and handle apart`() {
        val account = parse(MUSIC_MENU)

        assertEquals("Ada Lovelace", account?.name)
        assertEquals("ada@example.com", account?.email)
        assertEquals("@ada", account?.handle)
        assertEquals("https://yt3.example/ada88", account?.thumbnailUrl)
    }

    @Test
    fun `a response without an account header is not an account`() {
        assertNull(parse("""{"actions":[]}"""))
    }

    private companion object {
        const val YOUTUBE_MENU = """
        {"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{
          "header":{"activeAccountHeaderRenderer":{
            "accountName":{"simpleText":"김민수"},
            "channelHandle":{"simpleText":"@minsu"},
            "accountPhoto":{"thumbnails":[
              {"url":"https://yt3.example/s40","width":40,"height":40},
              {"url":"https://yt3.example/s88","width":88,"height":88}
            ]},
            "isSignedOut":false
          }},
          "sections":[]
        }}}}]}
        """

        const val MUSIC_MENU = """
        {"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{
          "header":{"activeAccountHeaderRenderer":{
            "accountName":{"runs":[{"text":"Ada Lovelace"}]},
            "email":{"runs":[{"text":"ada@example.com"}]},
            "channelHandle":{"runs":[{"text":"@ada"}]},
            "accountPhoto":{"thumbnails":[{"url":"https://yt3.example/ada88"}]}
          }}
        }}}}]}
        """
    }
}
