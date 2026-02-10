package com.carcassonne.lan.core

class UnionFind<T> {
    private val parent = mutableMapOf<T, T>()
    private val rank = mutableMapOf<T, Int>()

    fun add(item: T) {
        if (!parent.containsKey(item)) {
            parent[item] = item
            rank[item] = 0
        }
    }

    fun find(item: T): T {
        val p = parent[item] ?: error("UnionFind find() on unknown item: $item")
        if (p == item) return item
        val root = find(p)
        parent[item] = root
        return root
    }

    fun union(a: T, b: T) {
        add(a)
        add(b)
        var ra = find(a)
        var rb = find(b)
        if (ra == rb) return

        var rka = rank[ra] ?: 0
        var rkb = rank[rb] ?: 0
        if (rka < rkb) {
            val tmpR = ra
            ra = rb
            rb = tmpR
            val tmpK = rka
            rka = rkb
            rkb = tmpK
        }
        parent[rb] = ra
        if (rka == rkb) {
            rank[ra] = rka + 1
        }
    }
}
