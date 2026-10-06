package io.readx.app.pdf

import org.json.JSONArray
import org.json.JSONObject

enum class CropScope(val label:String) { PAGE("当前页"), ALL("全书"), ODD("奇数页"), EVEN("偶数页") }
data class PdfCropConfig(val enabled:Boolean=false,val automatic:Boolean=false,val all:CropRect?=null,val odd:CropRect?=null,val even:CropRect?=null,val pages:Map<Int,CropRect> = emptyMap()) {
    fun resolve(page:Int,detected:CropRect?=null):CropRect = if(!enabled) CropRect.FULL else pages[page] ?: (if((page+1)%2==1) odd else even) ?: all ?: (if(automatic) detected else null) ?: CropRect.FULL
    fun manual(page:Int,rect:CropRect,scope:CropScope)=when(scope) {CropScope.PAGE->copy(enabled=true,pages=pages+(page to rect));CropScope.ALL->copy(enabled=true,all=rect);CropScope.ODD->copy(enabled=true,odd=rect);CropScope.EVEN->copy(enabled=true,even=rect)}
    fun reset(page:Int,scope:CropScope)=when(scope) {CropScope.PAGE->copy(pages=pages-page);CropScope.ALL->copy(all=null);CropScope.ODD->copy(odd=null);CropScope.EVEN->copy(even=null)}
    fun json():String {
        fun box(r:CropRect)=JSONArray(listOf(r.left,r.top,r.right,r.bottom))
        val j=JSONObject().put("version",1).put("enabled",enabled).put("automatic",automatic)
        all?.let {j.put("all",box(it))};odd?.let {j.put("odd",box(it))};even?.let {j.put("even",box(it))}
        val p=JSONObject();pages.forEach {(i,r)->p.put(i.toString(),box(r))};return j.put("pages",p).toString()
    }
    companion object {
        fun parse(value:String):PdfCropConfig=runCatching {
            val j=JSONObject(value);require(j.getInt("version")==1)
            fun rect(a:JSONArray?)=a?.let {require(it.length()==4);CropRect(it.getDouble(0).toFloat(),it.getDouble(1).toFloat(),it.getDouble(2).toFloat(),it.getDouble(3).toFloat())}
            val p=j.optJSONObject("pages") ?: JSONObject();require(p.length()<=10000)
            val pages=p.keys().asSequence().associate {key->val page=key.toInt();require(page in 0..999999);page to rect(p.getJSONArray(key))!!}
            PdfCropConfig(j.optBoolean("enabled"),j.optBoolean("automatic"),rect(j.optJSONArray("all")),rect(j.optJSONArray("odd")),rect(j.optJSONArray("even")),pages)
        }.getOrDefault(PdfCropConfig())
    }
}
