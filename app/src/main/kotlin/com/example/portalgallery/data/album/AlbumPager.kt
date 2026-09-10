package com.example.portalgallery.data.album

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.JsonStreamParser
import java.io.StringReader

/**
 * Reads past the 300-item prefix a shared-album page serves, via the `snAcKc` RPC the
 * page declares for its own use.
 *
 * The page returns 300 items and a continuation token. Everything beyond that — for the
 * reference album, 19,974 items back to April 2019 — is only reachable by asking for the
 * next page the way the page's own JavaScript does: a `batchexecute` POST carrying that
 * token. 67 requests covers the whole album in about two minutes.
 *
 * This class owns the **envelope** and nothing else. The payload that comes back is the
 * identical `ds:1` array the HTML path already decodes, so entries go through
 * [SharedAlbumParser.decodePayload] rather than a second copy of that logic.
 *
 * Split deliberately into pure functions with the network at the edges: [parseEndpoint],
 * [buildRequestBody] and [parseResponse] are all testable without a device or a socket,
 * and each of the two things that actually went wrong when this was first executed
 * against the live album is caught by a test over one of them.
 *
 * **This depends on undocumented, unversioned internals and will break.** When it does,
 * the contract is the same as everywhere else here: a failed crawl must leave the library
 * exactly as it was. See [Page.complete].
 */
object AlbumPager {

    /**
     * Everything a request needs, all of it lifted from the share page.
     *
     * @param albumId the opaque `AF1Qip…` share identifier.
     * @param shareKey the access token from the page's own request tuple.
     * @param sourcePath the path the share link finally landed on, echoed back as a query
     *   parameter the way the page's own calls do.
     * @param sid `WIZ_global_data.FdrFJe` — the session id.
     * @param bl `WIZ_global_data.cfb2h` — the server build label. It changes when Google
     *   deploys, which is why it is read from the page every time rather than pinned.
     * @param pathPrefix `WIZ_global_data.Im6cmf`, normally `/_/PhotosUi`.
     */
    data class Endpoint(
        val albumId: String,
        val shareKey: String,
        val sourcePath: String,
        val sid: String,
        val bl: String,
        val pathPrefix: String,
    )

    /**
     * One page of results.
     *
     * @param complete true when the album ended here — the response carried no next
     *   token. **This is the only trustworthy signal that a crawl saw everything**, and
     *   §5.4's rule that a truncated crawl must never prune depends on it. A page that
     *   simply failed, timed out, or hit a cap is not complete, and callers must not
     *   infer completeness from "no error was thrown".
     */
    data class Page(
        val photos: List<SharedAlbumParser.Photo>,
        val nextToken: String?,
    ) {
        val complete: Boolean get() = nextToken == null
    }

    class PagerException(message: String) : Exception(message)

    private val DS1_REQUEST = Regex("""'ds:1'.*?request:(\[.*?])\s*}""", RegexOption.DOT_MATCHES_ALL)
    private val SERVICE_REQUESTS = Regex("""AF_dataServiceRequests\s*=\s*(\{.*?});""", RegexOption.DOT_MATCHES_ALL)
    private val WIZ_DATA = Regex("""WIZ_global_data\s*=\s*(\{.*?});""", RegexOption.DOT_MATCHES_ALL)

    /** The rpc the page declares for `ds:1`. Verified present in the committed fixture. */
    const val RPC_ID = "snAcKc"

    /**
     * Pulls the request material out of a share page.
     *
     * @param finalPath the path of the URL the share link redirected to — the caller has
     *   it from the fetch, and it cannot be recovered from the body.
     */
    fun parseEndpoint(html: String, finalPath: String): Endpoint? {
        val declarations = SERVICE_REQUESTS.find(html)?.groupValues?.get(1) ?: return null
        if (!declarations.contains(RPC_ID)) return null

        val tuple = DS1_REQUEST.find(declarations)?.groupValues?.get(1)
            ?.let { runCatching { JsonParser.parseString(it).asJsonArray }.getOrNull() }
            ?: return null
        // [albumId, null, null, shareKey] — the two nulls are why the token slot was got
        // wrong the first time. See buildRequestBody.
        if (tuple.size() < 4) return null

        val albumId = runCatching { tuple[0].asString }.getOrNull() ?: return null
        val shareKey = runCatching { tuple[3].asString }.getOrNull() ?: return null

        val wiz = WIZ_DATA.find(html)?.groupValues?.get(1)
            ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            ?: return null
        val sid = runCatching { wiz["FdrFJe"].asString }.getOrNull() ?: return null
        val bl = runCatching { wiz["cfb2h"].asString }.getOrNull() ?: return null
        val prefix = runCatching { wiz["Im6cmf"].asString }.getOrNull() ?: "/_/PhotosUi"

        return Endpoint(albumId, shareKey, finalPath, sid, bl, prefix)
    }

    /** The URL to POST to, minus the body. */
    fun buildUrl(endpoint: Endpoint, reqId: Int = 100000): String =
        "https://photos.google.com${endpoint.pathPrefix}/data/batchexecute" +
            "?rpcids=$RPC_ID" +
            "&source-path=${urlEncode(endpoint.sourcePath)}" +
            "&f.sid=${urlEncode(endpoint.sid)}" +
            "&bl=${urlEncode(endpoint.bl)}" +
            "&hl=en&_reqid=$reqId&rt=c"

    /**
     * The form body: `f.req=[[["snAcKc","<args>",null,"generic"]]]`, args being
     * `[albumId, token, null, shareKey]`.
     *
     * **The token goes in slot 1.** The page's own tuple is
     * `[albumId, null, null, shareKey]` and the obvious reading — that the token fills
     * "the null where a token would go" — picks slot 2 and is wrong.
     *
     * Getting it wrong is not a loud failure, which is why this has its own test. Slot 2
     * returns HTTP 200, a well-formed 300-entry payload, and a **fresh continuation token
     * every time** — so a crawl walks forever, reports steady progress, and collects the
     * same first 300 photos on every pass. Measured against the live album: five pages
     * fetched, 1,500 entries returned, 300 unique. Nothing at the transport layer can see
     * this; only comparing ids across pages can.
     */
    fun buildRequestBody(endpoint: Endpoint, token: String): String {
        val args = JsonArray().apply {
            add(endpoint.albumId)
            add(token)
            add(null as String?)
            add(endpoint.shareKey)
        }
        val envelope = JsonArray().apply {
            add(JsonArray().apply {
                add(JsonArray().apply {
                    add(RPC_ID)
                    add(args.toString())
                    add(null as String?)
                    add("generic")
                })
            })
        }
        return "f.req=${urlEncode(envelope.toString())}"
    }

    /**
     * Unwraps a batchexecute response.
     *
     * Three layers: the `)]}'` anti-JSON-hijacking prefix, then a sequence of JSON values
     * interleaved with bare integers, then — inside the `wrb.fr` frame for our rpc — the
     * payload as a *string* that has to be parsed again.
     *
     * **The integers are ignored on purpose.** They are meant to be the byte length of the
     * value that follows, and they are not usable: they count bytes against text, and are
     * off by one against the JSON they introduce (measured: a chunk declaring 190623 holds
     * JSON ending at 190622). Slicing by them fails to parse. Reading the stream one JSON
     * value at a time and letting the decoder find each value's end sidesteps the counts
     * entirely, and keeps working if Google adjusts the framing.
     *
     * @throws PagerException when no payload for [RPC_ID] is present. That is a structural
     *   failure — the caller must treat it as "read nothing", never as "the album ended".
     */
    fun parseResponse(body: String): Page {
        val stripped = body.substringAfter('\n', missingDelimiterValue = body)
            .let { if (body.startsWith(")]}'")) it else body }

        val parser = JsonStreamParser(StringReader(stripped))
        while (true) {
            // hasNext() itself throws on empty or truncated input, not just next(). A
            // half-delivered response has to come out of here as a PagerException like
            // any other structural failure — a raw Gson exception escaping would reach
            // the sync loop as something no caller is written to expect.
            if (!runCatching { parser.hasNext() }.getOrDefault(false)) break
            val element = runCatching { parser.next() }.getOrNull() ?: break
            // Bare integers are the length prefixes. Skip them; see the KDoc.
            if (!element.isJsonArray) continue

            for (frame in element.asJsonArray) {
                val f = frame as? JsonArray ?: continue
                if (f.size() < 3) continue
                if (runCatching { f[0].asString }.getOrNull() != "wrb.fr") continue
                if (runCatching { f[1].asString }.getOrNull() != RPC_ID) continue

                val inner = runCatching { JsonParser.parseString(f[2].asString).asJsonArray }
                    .getOrNull() ?: continue
                val decoded = SharedAlbumParser.decodePayload(inner)
                    ?: throw PagerException("payload for $RPC_ID was not shaped like ds:1")
                return Page(decoded.first, decoded.second)
            }
        }
        throw PagerException("no $RPC_ID payload in the response")
    }

    /**
     * Percent-encoding for form bodies and query values.
     *
     * Hand-rolled rather than `URLEncoder`, which encodes a space as `+` — correct for a
     * form body, wrong in a query string, and this builds both.
     */
    private fun urlEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c.isLetterOrDigit() && b.toInt() < 128 || c in "-_.~") append(c)
            else append('%').append("%02X".format(b.toInt() and 0xFF))
        }
    }
}
