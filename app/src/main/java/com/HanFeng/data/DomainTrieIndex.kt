package com.HanFeng.data

class DomainTrieIndex(
    blocked: Set<String>,
    userOwnedBlocked: Set<String>,
    importantBlocked: Set<String>,
    exceptions: Set<String>
) {
    companion object {
        private const val FLAG_BLOCKED = 1
        private const val FLAG_EXCEPTION = 2
        private const val FLAG_IMPORTANT = 4
        private const val FLAG_USER_OWNED = 8
        private const val WILDCARD_LABEL = "*"
    }

    private class TrieNode {
        // 惰性分配：广告域名 trie 大量节点是叶子或单链，空 HashMap 每个浪费 ~48B+，
        // 20 万级规则集下合计节省数十 MB
        var children: HashMap<String, TrieNode>? = null
        var flags = 0

        fun child(label: String): TrieNode? = children?.get(label)

        fun getOrCreateChild(label: String): TrieNode {
            var c = children?.get(label)
            if (c == null) {
                c = TrieNode()
                if (children == null) children = HashMap(4)
                children!!.put(label, c)
            }
            return c
        }
    }

    private val root = TrieNode()

    init {
        blocked.forEach { insert(it, FLAG_BLOCKED) }
        exceptions.forEach { insert(it, FLAG_EXCEPTION) }
        importantBlocked.forEach { insert(it, FLAG_IMPORTANT) }
        userOwnedBlocked.forEach { insert(it, FLAG_USER_OWNED) }
    }

    private fun insert(domain: String, flag: Int) {
        val labels = domain.split('.')
        var node = root
        for (i in labels.indices.reversed()) {
            node = node.getOrCreateChild(labels[i])
        }
        node.flags = node.flags or flag
    }

    fun hasBlocked(domain: String): Boolean = walk(domain) { (it.flags and (FLAG_BLOCKED or FLAG_IMPORTANT)) != 0 }

    fun hasException(domain: String): Boolean = walk(domain) { (it.flags and FLAG_EXCEPTION) != 0 }

    fun hasUserOwnedBlock(domain: String): Boolean = walk(domain) { (it.flags and FLAG_USER_OWNED) != 0 }

    fun hasImportantBlock(domain: String): Boolean = walk(domain) { (it.flags and FLAG_IMPORTANT) != 0 }

    private inline fun walk(domain: String, predicate: (TrieNode) -> Boolean): Boolean {
        val labels = domain.split('.')
        var node = root
        for (i in labels.indices.reversed()) {
            val specific = node.child(labels[i])
            if (specific != null) {
                node = specific
                if (predicate(node)) return true
            } else {
                node = node.child(WILDCARD_LABEL) ?: return false
                if (predicate(node)) return true
            }
        }
        return predicate(node)
    }
}
