package com.bodyrecongnize.demo.lab

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LabStore(context: Context) {
    val root = File(context.filesDir, "ai_lab").apply { mkdirs() }
    fun folder(id: String) = File(root, id).also { require(it.canonicalFile.parentFile == root.canonicalFile) }
    fun save(run: LabRun) {
        val folder = folder(run.id).apply { mkdirs() }
        val json = JSONObject().apply {
            put("schema", 2); put("id", run.id); put("source", run.source); put("condition", run.condition)
            put("elapsedMs", run.elapsedMs); put("createdAt", run.createdAt)
            put("truth", run.truth ?: JSONObject.NULL); put("expected", run.expected?.name ?: JSONObject.NULL)
            put("baselineId", run.baselineId ?: JSONObject.NULL)
            put("frames", JSONArray(run.frames.map { frame -> JSONObject().apply {
                put("cut", frame.cut); put("t", frame.timeMs); put("w", frame.width); put("h", frame.height); put("image", frame.image)
                put("inferenceMs", frame.inferenceMs); put("points", JSONArray(frame.points.map { it.toDouble() }))
            } }))
            put("events", JSONArray(run.events.map { e -> JSONObject().apply {
                put("id", e.id); put("kind", e.kind.name); put("start", e.start); put("end", e.end)
                put("peak", e.peak); put("progress", e.peakProgress); put("status", e.status)
                put("reason", e.reason); put("match", e.match)
            } }))
            put("segments", JSONArray(run.segments.map { s -> JSONObject().apply {
                put("kind", s.kind?.name ?: JSONObject.NULL); put("start", s.start); put("end", s.end)
                put("eventIds", JSONArray(s.eventIds))
            } }))
        }
        val file = AtomicFile(File(folder, "run.json"))
        val stream = file.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        root.listFiles()?.filter { File(it, "run.json").isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(5)?.forEach { it.deleteRecursively() }
    }
    fun recent(): List<LabRun> = root.listFiles()?.filter { File(it, "run.json").isFile }
        ?.sortedByDescending { it.lastModified() }?.mapNotNull { runCatching { load(it.name) }.getOrNull() }.orEmpty()
    fun image(run: LabRun, frame: LabFrame): File = File(folder(run.id), frame.image).also {
        require(it.canonicalFile.parentFile == folder(run.id).canonicalFile)
    }
    private fun load(id: String): LabRun {
        val json = JSONObject(File(folder(id), "run.json").readText())
        require(json.optInt("schema") == 2) { "旧版实验记录需重新分析" }
        val frames = json.getJSONArray("frames").objects().map { f ->
            val p = f.getJSONArray("points"); require(p.length() == 99)
            LabFrame(f.getLong("t"), f.getInt("w"), f.getInt("h"), FloatArray(99) { p.getDouble(it).toFloat() }, f.getString("image"), f.getDouble("inferenceMs"), f.optBoolean("cut"))
        }
        val events = json.getJSONArray("events").objects().map { e -> LabEvent(e.getString("id"), LabKind.valueOf(e.getString("kind")),
            e.getInt("start"), e.getInt("end"), e.getInt("peak"), e.getDouble("progress").toFloat(), e.getString("status"), e.getString("reason"), e.getDouble("match").toFloat()) }
        val segments = json.getJSONArray("segments").objects().map { s -> val ids = s.getJSONArray("eventIds")
            LabSegment(if (s.isNull("kind")) null else LabKind.valueOf(s.getString("kind")), s.getInt("start"), s.getInt("end"), (0 until ids.length()).map { ids.getString(it) }) }
        return LabRun(id, json.getString("source"), json.getString("condition"), frames, events, segments,
            json.getLong("elapsedMs"), json.getLong("createdAt"), if (json.isNull("truth")) null else json.getInt("truth"),
            if (json.isNull("expected")) null else LabKind.valueOf(json.getString("expected")),
            if (json.isNull("baselineId")) null else json.getString("baselineId"))
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
