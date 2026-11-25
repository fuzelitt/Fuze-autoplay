package com.phiauto

import org.json.JSONObject

/** One finger path inside a gesture window (see python/touch_plan.py). */
class StrokeSpec(
    val id: Int,
    val t0: Long,
    val t1: Long,
    val cont: Boolean,   // continues a stroke from the previous window
    val will: Boolean,   // continues into the next window
    val xs: FloatArray,
    val ys: FloatArray,
)

class Window(val w0: Long, val strokes: List<StrokeSpec>)

class Plan(
    val first: Long,
    val last: Long,
    val screenW: Int,
    val screenH: Int,
    val touches: Int,
    val windows: List<Window>,
) {
    companion object {
        fun parse(json: String): Plan {
            val o = JSONObject(json)
            val ws = o.getJSONArray("windows")
            val windows = ArrayList<Window>(ws.length())
            for (i in 0 until ws.length()) {
                val w = ws.getJSONObject(i)
                val ss = w.getJSONArray("strokes")
                val strokes = ArrayList<StrokeSpec>(ss.length())
                for (j in 0 until ss.length()) {
                    val s = ss.getJSONObject(j)
                    val p = s.getJSONArray("p")
                    val xs = FloatArray(p.length())
                    val ys = FloatArray(p.length())
                    for (k in 0 until p.length()) {
                        val pt = p.getJSONArray(k)
                        xs[k] = pt.getInt(1).toFloat()
                        ys[k] = pt.getInt(2).toFloat()
                    }
                    strokes.add(
                        StrokeSpec(
                            s.getInt("id"), s.getLong("t0"), s.getLong("t1"),
                            s.getBoolean("cont"), s.getBoolean("will"), xs, ys
                        )
                    )
                }
                windows.add(Window(w.getLong("w0"), strokes))
            }
            val screen = o.getJSONArray("screen")
            return Plan(
                o.getLong("first"), o.getLong("last"),
                screen.getInt(0), screen.getInt(1), o.getInt("touches"), windows
            )
        }
    }
}
