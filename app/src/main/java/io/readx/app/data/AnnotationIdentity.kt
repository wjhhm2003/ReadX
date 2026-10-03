package io.readx.app.data

import org.json.JSONObject
import java.security.MessageDigest

/** Persisted opaque RGB colors; styling is applied by the renderer, never interpolated from arbitrary input. */
object MarkColor {
    val choices=listOf("#FFD240", "#75D798", "#75BEFF", "#FF91B4", "#C4A2FF", "#FFA86C")
    fun normalize(value: String)=value.uppercase().takeIf {it in choices} ?: choices.first()
}
object AnnotationIdentity {
    private fun digest(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {"%02x".format(it)}
    fun text(chapter: Int,start: Int,end: Int,quote: String)=digest(listOf("dom-v1",chapter,start,end,quote).joinToString("\u0000"))
    fun key(chapter: Int,kind: String,locator: String,id: String): String {
        if(kind=="BOOKMARK") return "bookmark:$id"
        val identity=runCatching {
            val value=JSONObject(locator)
            if(value.has("start") && value.has("end"))
                listOf("dom-v1",chapter,value.getInt("start"),value.getInt("end"),value.getString("quote")).joinToString("\u0000")
            else if(value.optString("format")=="PDF") {
                val boxes=value.getJSONArray("rects")
                val parts=(0 until boxes.length()).map {i->val box=boxes.getJSONObject(i)
                    listOf(box.getInt("page").toLong(), *arrayOf("left","top","right","bottom").map { kotlin.math.round(box.getDouble(it)*10000).toLong() }.toTypedArray()).joinToString(",")
                }.sorted()
                "pdf-v1|"+parts.joinToString(";")
            } else return@runCatching "legacy:$id"
        }.getOrDefault("legacy:$id")
        return digest(identity)
    }
}
