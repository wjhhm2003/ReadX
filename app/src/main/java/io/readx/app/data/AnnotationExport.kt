package io.readx.app.data

/** Plain, deterministic sidecar export. Historical duplicate notes stay distinct. */
object AnnotationExport {
    fun heading(title:String,markdown:Boolean)=if(markdown) "# ${escape(title)}\n\n" else "$title\n\n"
    fun entry(value:Annotation,format:String,markdown:Boolean):String {
        val type=when(value.kind) {"HIGHLIGHT"->"高亮";"UNDERLINE"->"划线";"NOTE"->"笔记";else->"位置书签"}
        val position=if(format=="PDF") "原文第 ${value.chapter+1} 页" else "第 ${value.chapter+1} 章 · ${value.label}"
        return if(markdown) buildString {
            append("## ${escape(type)} · ${escape(position)}\n\n")
            if(value.quote.isNotBlank()) {value.quote.lines().forEach {append("> ${escape(it)}\n")};append('\n')}
            if(value.note.isNotBlank())append("${escape(value.note)}\n\n")
            append("标记色：${escape(value.color)}\n\n")
        } else buildString {
            append("[$type] $position\n")
            if(value.quote.isNotBlank())append("${value.quote}\n")
            if(value.note.isNotBlank())append("笔记：${value.note}\n")
            append("标记色：${value.color}\n\n")
        }
    }
    private fun escape(text:String)=text.replace("\\","\\\\").replace("*","\\*").replace("_","\\_").replace("`","\\`").replace("[","\\[").replace("]","\\]").replace("<","&lt;").replace(">","&gt;").replace("#","\\#")
}
