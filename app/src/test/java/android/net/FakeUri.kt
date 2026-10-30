package android.net

import android.os.Parcel

/**
 * A minimal [Uri] for JVM tests.
 *
 * The unit-test android.jar returns null from `Uri.parse`, so a Media3
 * [androidx.media3.datasource.DataSpec] cannot be built from a string in a
 * plain JVM test. This subclass supplies only what the code under test reads.
 */
class FakeUri(private val url: String) : Uri() {
    private val parsed = java.net.URI(url)

    override fun buildUpon(): Uri.Builder = throw UnsupportedOperationException("not needed")
    override fun getAuthority(): String? = parsed.authority
    override fun getEncodedAuthority(): String? = parsed.rawAuthority
    override fun getEncodedFragment(): String? = parsed.rawFragment
    override fun getEncodedPath(): String? = parsed.rawPath
    override fun getEncodedQuery(): String? = parsed.rawQuery
    override fun getEncodedSchemeSpecificPart(): String? = parsed.rawSchemeSpecificPart
    override fun getEncodedUserInfo(): String? = parsed.rawUserInfo
    override fun getFragment(): String? = parsed.fragment
    override fun getHost(): String? = parsed.host
    override fun getLastPathSegment(): String? = parsed.path?.substringAfterLast('/')
    override fun getPath(): String? = parsed.path
    override fun getPathSegments(): List<String> =
        parsed.path.orEmpty().split('/').filter { it.isNotEmpty() }
    override fun getPort(): Int = parsed.port
    override fun getQuery(): String? = parsed.query
    override fun getScheme(): String? = parsed.scheme
    override fun getSchemeSpecificPart(): String? = parsed.schemeSpecificPart
    override fun getUserInfo(): String? = parsed.userInfo
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = !parsed.isAbsolute
    override fun toString(): String = url
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit

    override fun getQueryParameter(key: String): String? =
        getQuery()?.split('&')
            ?.mapNotNull { part ->
                val name = part.substringBefore('=', "")
                if (name == key) part.substringAfter('=', "") else null
            }
            ?.firstOrNull()
}
