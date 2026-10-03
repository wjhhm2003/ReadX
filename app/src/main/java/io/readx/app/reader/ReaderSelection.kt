package io.readx.app.reader

import androidx.compose.ui.geometry.Rect
import org.json.JSONObject

/** Read-only selection result from the bundled script; no script bridge or book-supplied commands. */
data class ReaderSelection(val anchor: TextAnchor,val bounds: Rect,val annotationIds: List<String>,val fromMark: Boolean=false,val primaryId: String?=null) {
    companion object {
        fun parse(value: String): ReaderSelection?=runCatching {
            val json=JSONObject(value);val anchor=TextAnchor.fromJson(json.getJSONObject("anchor")) ?: return@runCatching null
            val rect=json.getJSONArray("rect")
            val coords=(0..3).map {rect.getDouble(it).toFloat()};require(coords.all {it.isFinite() && kotlin.math.abs(it)<1_000_000})
            val bounds=Rect(coords[0],coords[1],coords[2],coords[3]);require(bounds.width>=0 && bounds.height>=0)
            val ids=json.optJSONArray("ids")
            ReaderSelection(anchor,bounds,(0 until (ids?.length() ?: 0).coerceAtMost(2000)).mapNotNull {i->ids!!.getString(i).takeIf {it.matches(Regex("[A-Za-z0-9_-]{1,128}"))}}.distinct(),json.optBoolean("fromMark"),json.optString("primaryId").takeIf {it.isNotBlank()})
        }.getOrNull()
    }
}
