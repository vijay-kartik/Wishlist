package com.example.app.wishlist.ui.debug

import com.example.app.wishlist.data.db.entity.KgEdge
import com.example.app.wishlist.data.db.entity.KgNode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A laid-out node position in the layout's own coordinate space. */
data class LayoutPoint(val x: Float, val y: Float)

/**
 * Graph layout for the Visualize tab, ported from the design's JavaScript.
 *
 * Deliberately free of Compose and ObjectBox types beyond the entity classes: this is the
 * most numerically fiddly code on the screen, it is pure input-to-output, and keeping it
 * that way means it can be exercised from a JVM test without a device.
 *
 * Everything works in a fixed [WIDTH] x [HEIGHT] space rather than the real canvas size.
 * The renderer scales at draw time, so rotating the device or changing the panel height
 * does not re-run the simulation — which matters, because [force] is O(n²) per iteration
 * and is far too slow to run during a layout pass.
 */
object DebugGraphLayout {

    /** The design's SVG viewBox. Positions are scaled into the real canvas at draw time. */
    const val WIDTH = 378f
    const val HEIGHT = 430f

    private const val ITERATIONS = 320
    private const val SEPARATION_PASSES = 40
    private const val PADDING = 30f

    /**
     * Force-directed layout: Fruchterman-Reingold style repulsion and attraction, then a
     * separation pass, then a fit-to-bounds.
     *
     * The separation pass exists because the force stage alone reliably leaves a few pairs
     * overlapping, and two circles drawn on top of each other read as one node with a
     * strange outline — the single most misleading thing this view could do.
     *
     * @param seed varies the initial ring placement so the reseed button can shake a
     *   tangled layout loose. Same seed gives the same layout, which keeps the view stable
     *   across recompositions.
     */
    fun force(
        nodes: List<KgNode>,
        edges: List<KgEdge>,
        seed: Int,
    ): Map<Long, LayoutPoint> {
        val n = nodes.size
        if (n == 0) return emptyMap()

        // Deliberately a fixed LCG rather than Random(seed): the design's layout is tuned
        // against this exact sequence, and Kotlin's Random would shift every position.
        // Long, not Int — the multiply overflows Int on the second call.
        var s: Long = seed.toLong() * 9301L + 49297L
        fun rnd(): Float {
            s = (s * 9301L + 49297L) % 233280L
            return (s.toDouble() / 233280.0).toFloat()
        }

        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val vxs = FloatArray(n)
        val vys = FloatArray(n)
        val indexOf = HashMap<Long, Int>(n * 2)

        nodes.forEachIndexed { i, node ->
            indexOf[node.graphKey] = i
            val angle = (i.toFloat() / n) * 2f * Math.PI.toFloat() + rnd() * 0.6f
            val radius = min(WIDTH, HEIGHT) * (0.18f + rnd() * 0.22f)
            xs[i] = WIDTH / 2f + cos(angle) * radius
            ys[i] = HEIGHT / 2f + sin(angle) * radius
        }

        // Edges whose endpoints are both visible. A filtered-out node type leaves dangling
        // edges behind, and pulling toward a node that is not drawn warps the layout.
        val links = edges.mapNotNull { edge ->
            val a = indexOf[edge.fromKey] ?: return@mapNotNull null
            val b = indexOf[edge.toKey] ?: return@mapNotNull null
            if (a == b) null else a to b
        }

        val k = sqrt((WIDTH * HEIGHT) / n) * 0.62f

        repeat(ITERATIONS) { iteration ->
            val cooling = 1f - iteration.toFloat() / ITERATIONS

            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var dx = xs[i] - xs[j]
                    var dy = ys[i] - ys[j]
                    val dist = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: 0.01f
                    val repulsion = (k * k) / dist * 0.9f
                    dx /= dist
                    dy /= dist
                    vxs[i] += dx * repulsion
                    vys[i] += dy * repulsion
                    vxs[j] -= dx * repulsion
                    vys[j] -= dy * repulsion
                }
            }

            links.forEach { (a, b) ->
                var dx = xs[b] - xs[a]
                var dy = ys[b] - ys[a]
                val dist = sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: 0.01f
                val attraction = (dist * dist) / k * 0.055f
                dx /= dist
                dy /= dist
                vxs[a] += dx * attraction
                vys[a] += dy * attraction
                vxs[b] -= dx * attraction
                vys[b] -= dy * attraction
            }

            for (i in 0 until n) {
                // Gentle pull to centre, so disconnected components do not drift off-canvas.
                vxs[i] += (WIDTH / 2f - xs[i]) * 0.026f
                vys[i] += (HEIGHT / 2f - ys[i]) * 0.026f
                val speed = sqrt(vxs[i] * vxs[i] + vys[i] * vys[i]).takeIf { it > 0f } ?: 0.01f
                val cap = min(speed, k * 0.35f * cooling + 0.4f) / speed
                xs[i] += vxs[i] * cap * 0.5f
                ys[i] += vys[i] * cap * 0.5f
                vxs[i] *= 0.72f
                vys[i] *= 0.72f
            }
        }

        val minDist = maxOf(30f, min(46f, sqrt((WIDTH * HEIGHT) / n) * 0.55f))
        repeat(SEPARATION_PASSES) {
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var dx = xs[j] - xs[i]
                    var dy = ys[j] - ys[i]
                    var dist = sqrt(dx * dx + dy * dy)
                    if (dist < 0.01f) {
                        // Exactly coincident: nudge apart deterministically rather than
                        // dividing by zero.
                        dx = if (i % 2 == 0) -0.7f else 0.7f
                        dy = 0.7f
                        dist = 1f
                    }
                    if (dist < minDist) {
                        val push = (minDist - dist) / dist * 0.5f
                        val ox = dx * push
                        val oy = dy * push
                        xs[i] -= ox
                        ys[i] -= oy
                        xs[j] += ox
                        ys[j] += oy
                    }
                }
            }
        }

        return fitToBounds(nodes, xs, ys)
    }

    /**
     * Concentric rings by hop distance from [centerKey] — the "what is around this person"
     * view, where the force layout answers "what is the overall shape".
     *
     * Nodes unreachable from the centre land in an outermost ring rather than being
     * dropped: an isolated node is a real and interesting graph state, and silently
     * omitting it would make the view lie about what is stored.
     */
    fun radial(
        nodes: List<KgNode>,
        edges: List<KgEdge>,
        centerKey: Long,
    ): Map<Long, LayoutPoint> {
        if (nodes.isEmpty()) return emptyMap()
        val present = nodes.mapTo(HashSet()) { it.graphKey }
        if (centerKey !in present) return force(nodes, edges, seed = 1)

        val depth = HashMap<Long, Int>()
        depth[centerKey] = 0
        var frontier = listOf(centerKey)
        for (d in 1..MAX_RING) {
            val next = ArrayList<Long>()
            frontier.forEach { key ->
                edges.forEach { edge ->
                    val other = when (key) {
                        edge.fromKey -> edge.toKey
                        edge.toKey -> edge.fromKey
                        else -> null
                    }
                    if (other != null && other in present && !depth.containsKey(other)) {
                        depth[other] = d
                        next += other
                    }
                }
            }
            if (next.isEmpty()) break
            frontier = next
        }

        val rings = nodes.groupBy { depth[it.graphKey] ?: (MAX_RING + 1) }
        val maxRadius = min(WIDTH, HEIGHT) / 2f - 26f
        val positions = HashMap<Long, LayoutPoint>(nodes.size * 2)

        rings.forEach { (ring, members) ->
            if (ring == 0) {
                positions[members.first().graphKey] = LayoutPoint(WIDTH / 2f, HEIGHT / 2f)
                return@forEach
            }
            val radius = (maxRadius / (MAX_RING + 1)) * ring
            members.forEachIndexed { i, node ->
                // The half-radian stagger on alternate rings stops nodes on adjacent rings
                // lining up radially, which otherwise reads as a spoke rather than a ring.
                val angle = (i.toFloat() / members.size) * 2f * Math.PI.toFloat() -
                    (Math.PI.toFloat() / 2f) + (ring % 2) * 0.3f
                positions[node.graphKey] = LayoutPoint(
                    x = WIDTH / 2f + cos(angle) * radius,
                    y = HEIGHT / 2f + sin(angle) * radius,
                )
            }
        }
        return positions
    }

    private const val MAX_RING = 3

    /** Scales and centres the settled layout into the canvas, capped so tiny graphs do not balloon. */
    private fun fitToBounds(
        nodes: List<KgNode>,
        xs: FloatArray,
        ys: FloatArray,
    ): Map<Long, LayoutPoint> {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in nodes.indices) {
            minX = min(minX, xs[i]); maxX = maxOf(maxX, xs[i])
            minY = min(minY, ys[i]); maxY = maxOf(maxY, ys[i])
        }
        val spanX = maxX - minX
        val spanY = maxY - minY
        val scaleX = if (spanX > 1f) (WIDTH - PADDING * 2) / spanX else 1f
        val scaleY = if (spanY > 1f) (HEIGHT - PADDING * 2) / spanY else 1f
        val scale = min(min(scaleX, scaleY), 1.6f)

        val offsetX = (WIDTH - PADDING * 2 - spanX * scale) / 2f
        val offsetY = (HEIGHT - PADDING * 2 - spanY * scale) / 2f

        return nodes.indices.associate { i ->
            nodes[i].graphKey to LayoutPoint(
                x = PADDING + (xs[i] - minX) * scale + offsetX,
                y = PADDING + (ys[i] - minY) * scale + offsetY,
            )
        }
    }

    /**
     * How many nodes the force layout will lay out before it is capped.
     *
     * The simulation is O(n²) per iteration over 320 iterations, so 500 nodes is roughly
     * 80M distance calculations — seconds of work on a phone, on top of being unreadable
     * at 378dp wide. The ViewModel keeps the highest-degree nodes and says how many it
     * dropped; a silently truncated graph would be worse than a slow one.
     */
    const val MAX_RENDERED_NODES = 120
}
