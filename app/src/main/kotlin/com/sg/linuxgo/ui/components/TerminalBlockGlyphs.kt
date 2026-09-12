package com.sg.linuxgo.ui.components

import kotlin.math.max
import kotlin.math.min

/**
 * Cell-filling geometry for TUI block / box / braille glyphs.
 *
 * Font █▀▄ and │─ rarely ink the full cell, so stacked logo art (OpenCode)
 * shows horizontal hairlines. These fills go edge-to-edge.
 */
object TerminalBlockGlyphs {

    data class CellFill(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val alpha: Float = 1f
    )

    fun isSpecial(codePoint: Int): Boolean = when (codePoint) {
        in 0x23BA..0x23BD -> true
        in 0x2500..0x259F -> true
        in 0x2800..0x28FF -> true
        else -> false
    }

    fun fills(
        codePoint: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float
    ): List<CellFill>? {
        if (width <= 0f || height <= 0f) return null
        return when (codePoint) {
            in 0x2580..0x259F -> blockElements(codePoint, left, top, width, height)
            in 0x2500..0x257F -> boxDrawing(codePoint, left, top, width, height)
            in 0x2800..0x28FF -> braille(codePoint, left, top, width, height)
            in 0x23BA..0x23BD -> scanLines(codePoint, left, top, width, height)
            else -> null
        }
    }

    fun draw(
        canvas: android.graphics.Canvas,
        paint: android.graphics.Paint,
        codePoint: Int,
        left: Float,
        top: Float,
        width: Float,
        height: Float
    ): Boolean {
        val rects = fills(codePoint, left, top, width, height) ?: return false
        val oldAa = paint.isAntiAlias
        val oldAlpha = paint.alpha
        paint.isAntiAlias = false
        paint.style = android.graphics.Paint.Style.FILL
        for (r in rects) {
            paint.alpha = (oldAlpha * r.alpha).toInt().coerceIn(0, 255)
            canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
        }
        paint.alpha = oldAlpha
        paint.isAntiAlias = oldAa
        return true
    }

    private fun blockElements(
        cp: Int,
        l: Float,
        t: Float,
        w: Float,
        h: Float
    ): List<CellFill> {
        fun band(y0: Float, y1: Float) = CellFill(l, t + h * y0, l + w, t + h * y1)
        fun slab(x0: Float, x1: Float) = CellFill(l + w * x0, t, l + w * x1, t + h)
        fun quad(col: Int, row: Int): CellFill {
            val x0 = l + w * (col * 0.5f)
            val y0 = t + h * (row * 0.5f)
            return CellFill(x0, y0, x0 + w * 0.5f, y0 + h * 0.5f)
        }
        return when (cp) {
            0x2580 -> listOf(band(0f, 0.5f))
            0x2581 -> listOf(band(7f / 8f, 1f))
            0x2582 -> listOf(band(0.75f, 1f))
            0x2583 -> listOf(band(5f / 8f, 1f))
            0x2584 -> listOf(band(0.5f, 1f))
            0x2585 -> listOf(band(3f / 8f, 1f))
            0x2586 -> listOf(band(0.25f, 1f))
            0x2587 -> listOf(band(1f / 8f, 1f))
            0x2588 -> listOf(band(0f, 1f))
            0x2589 -> listOf(slab(0f, 7f / 8f))
            0x258A -> listOf(slab(0f, 0.75f))
            0x258B -> listOf(slab(0f, 5f / 8f))
            0x258C -> listOf(slab(0f, 0.5f))
            0x258D -> listOf(slab(0f, 3f / 8f))
            0x258E -> listOf(slab(0f, 0.25f))
            0x258F -> listOf(slab(0f, 1f / 8f))
            0x2590 -> listOf(slab(0.5f, 1f))
            0x2591 -> listOf(CellFill(l, t, l + w, t + h, alpha = 0.25f))
            0x2592 -> listOf(CellFill(l, t, l + w, t + h, alpha = 0.50f))
            0x2593 -> listOf(CellFill(l, t, l + w, t + h, alpha = 0.75f))
            0x2594 -> listOf(band(0f, 1f / 8f))
            0x2595 -> listOf(slab(7f / 8f, 1f))
            0x2596 -> listOf(quad(0, 1))
            0x2597 -> listOf(quad(1, 1))
            0x2598 -> listOf(quad(0, 0))
            0x2599 -> listOf(quad(0, 0), quad(0, 1), quad(1, 1))
            0x259A -> listOf(quad(0, 0), quad(1, 1))
            0x259B -> listOf(quad(0, 0), quad(1, 0), quad(0, 1))
            0x259C -> listOf(quad(0, 0), quad(1, 0), quad(1, 1))
            0x259D -> listOf(quad(1, 0))
            0x259E -> listOf(quad(1, 0), quad(0, 1))
            0x259F -> listOf(quad(1, 0), quad(0, 1), quad(1, 1))
            else -> listOf(band(0f, 1f))
        }
    }

    private const val NONE = 0
    private const val LIGHT = 1
    private const val HEAVY = 2
    private const val DOUBLE = 3

    private fun boxDrawing(
        cp: Int,
        l: Float,
        t: Float,
        w: Float,
        h: Float
    ): List<CellFill>? {
        val packed = boxPack(cp) ?: return null
        val n = packed and 3
        val e = (packed shr 2) and 3
        val s = (packed shr 4) and 3
        val west = (packed shr 6) and 3
        val light = max(1f, min(w, h) * 0.12f)
        val heavy = light * 2.15f
        val out = ArrayList<CellFill>(6)
        fun thick(kind: Int) = if (kind == HEAVY) heavy else light
        fun arm(dir: Char, kind: Int) {
            if (kind == NONE) return
            if (kind == DOUBLE) {
                doubleArm(out, l, t, w, h, dir, light)
            } else {
                out += singleArm(l, t, w, h, dir, thick(kind))
            }
        }
        arm('N', n)
        arm('E', e)
        arm('S', s)
        arm('W', west)
        return out
    }

    private fun pack(n: Int, e: Int, s: Int, w: Int): Int =
        n or (e shl 2) or (s shl 4) or (w shl 6)

    private fun boxPack(cp: Int): Int? {
        val L = LIGHT
        val H = HEAVY
        val D = DOUBLE
        return when (cp) {
            0x2500, 0x2504, 0x2508 -> pack(NONE, L, NONE, L)
            0x2501, 0x2505, 0x2509 -> pack(NONE, H, NONE, H)
            0x2502, 0x2506, 0x250A -> pack(L, NONE, L, NONE)
            0x2503, 0x2507, 0x250B -> pack(H, NONE, H, NONE)
            0x250C, 0x250D -> pack(NONE, L, L, NONE)
            0x250E, 0x250F -> pack(NONE, L, H, NONE)
            0x2510, 0x2511 -> pack(NONE, NONE, L, L)
            0x2512, 0x2513 -> pack(NONE, NONE, H, L)
            0x2514, 0x2515 -> pack(L, L, NONE, NONE)
            0x2516, 0x2517 -> pack(H, L, NONE, NONE)
            0x2518, 0x2519 -> pack(L, NONE, NONE, L)
            0x251A, 0x251B -> pack(H, NONE, NONE, L)
            0x251C -> pack(L, L, L, NONE)
            0x251D -> pack(L, H, L, NONE)
            0x2520 -> pack(H, L, H, NONE)
            0x2523 -> pack(H, H, H, NONE)
            0x2524 -> pack(L, NONE, L, L)
            0x2525 -> pack(L, NONE, L, H)
            0x2528 -> pack(H, NONE, H, L)
            0x252B -> pack(H, NONE, H, H)
            0x252C -> pack(NONE, L, L, L)
            0x252F -> pack(NONE, H, L, H)
            0x2530 -> pack(NONE, L, H, L)
            0x2533 -> pack(NONE, H, H, H)
            0x2534 -> pack(L, L, NONE, L)
            0x2537 -> pack(L, H, NONE, H)
            0x2538 -> pack(H, L, NONE, L)
            0x253B -> pack(H, H, NONE, H)
            0x253C -> pack(L, L, L, L)
            0x254B -> pack(H, H, H, H)
            0x2550 -> pack(NONE, D, NONE, D)
            0x2551 -> pack(D, NONE, D, NONE)
            0x2552, 0x2553, 0x2554 -> pack(NONE, D, D, NONE)
            0x2555, 0x2556, 0x2557 -> pack(NONE, NONE, D, D)
            0x2558, 0x2559, 0x255A -> pack(D, D, NONE, NONE)
            0x255B, 0x255C, 0x255D -> pack(D, NONE, NONE, D)
            0x255E, 0x255F, 0x2560 -> pack(D, D, D, NONE)
            0x2561, 0x2562, 0x2563 -> pack(D, NONE, D, D)
            0x2564, 0x2565, 0x2566 -> pack(NONE, D, D, D)
            0x2567, 0x2568, 0x2569 -> pack(D, D, NONE, D)
            0x256A, 0x256B, 0x256C -> pack(D, D, D, D)
            0x256D -> pack(NONE, L, L, NONE)
            0x256E -> pack(NONE, NONE, L, L)
            0x256F -> pack(L, NONE, NONE, L)
            0x2570 -> pack(L, L, NONE, NONE)
            0x2574 -> pack(NONE, L, NONE, NONE)
            0x2575 -> pack(L, NONE, NONE, NONE)
            0x2576 -> pack(NONE, NONE, NONE, L)
            0x2577 -> pack(NONE, NONE, L, NONE)
            0x2578 -> pack(NONE, H, NONE, NONE)
            0x2579 -> pack(H, NONE, NONE, NONE)
            0x257A -> pack(NONE, NONE, NONE, H)
            0x257B -> pack(NONE, NONE, H, NONE)
            0x257C -> pack(NONE, H, NONE, L)
            0x257D -> pack(L, NONE, H, NONE)
            0x257E -> pack(NONE, L, NONE, H)
            0x257F -> pack(H, NONE, L, NONE)
            else -> pack(L, L, L, L).takeIf {
                cp in 0x251E..0x254A
            }
        }
    }

    private fun singleArm(
        l: Float,
        t: Float,
        w: Float,
        h: Float,
        dir: Char,
        thick: Float
    ): CellFill {
        val cx = l + w * 0.5f
        val cy = t + h * 0.5f
        val half = thick * 0.5f
        return when (dir) {
            'N' -> CellFill(cx - half, t, cx + half, cy + half)
            'S' -> CellFill(cx - half, cy - half, cx + half, t + h)
            'W' -> CellFill(l, cy - half, cx + half, cy + half)
            else -> CellFill(cx - half, cy - half, l + w, cy + half)
        }
    }

    private fun doubleArm(
        out: MutableList<CellFill>,
        l: Float,
        t: Float,
        w: Float,
        h: Float,
        dir: Char,
        thick: Float
    ) {
        val gap = max(thick * 1.6f, min(w, h) * 0.16f)
        val cx = l + w * 0.5f
        val cy = t + h * 0.5f
        val half = thick * 0.5f
        when (dir) {
            'N', 'S' -> {
                val y0 = if (dir == 'N') t else cy - half
                val y1 = if (dir == 'N') cy + half else t + h
                out += CellFill(cx - gap - half, y0, cx - gap + half, y1)
                out += CellFill(cx + gap - half, y0, cx + gap + half, y1)
            }
            else -> {
                val x0 = if (dir == 'W') l else cx - half
                val x1 = if (dir == 'W') cx + half else l + w
                out += CellFill(x0, cy - gap - half, x1, cy - gap + half)
                out += CellFill(x0, cy + gap - half, x1, cy + gap + half)
            }
        }
    }

    private fun braille(
        cp: Int,
        l: Float,
        t: Float,
        w: Float,
        h: Float
    ): List<CellFill> {
        val bits = cp - 0x2800
        if (bits == 0) return emptyList()
        val cw = w * 0.5f
        val rh = h * 0.25f
        val out = ArrayList<CellFill>(8)
        // 2×4 pixel grid that tiles the cell (TUI graphics, not printed braille).
        val map = intArrayOf(
            0x01, 0x08,
            0x02, 0x10,
            0x04, 0x20,
            0x40, 0x80
        )
        for (row in 0 until 4) {
            for (col in 0 until 2) {
                if (bits and map[row * 2 + col] != 0) {
                    val x = l + col * cw
                    val y = t + row * rh
                    out += CellFill(x, y, x + cw, y + rh)
                }
            }
        }
        return out
    }

    private fun scanLines(
        cp: Int,
        l: Float,
        t: Float,
        w: Float,
        h: Float
    ): List<CellFill> {
        val thick = max(1f, h * 0.08f)
        val frac = when (cp) {
            0x23BA -> 0.125f
            0x23BB -> 0.375f
            0x23BC -> 0.625f
            else -> 0.875f
        }
        val y = t + h * frac
        return listOf(CellFill(l, y - thick * 0.5f, l + w, y + thick * 0.5f))
    }
}
