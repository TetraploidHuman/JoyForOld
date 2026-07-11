package com.tetraploid.joyforold.offline.nlu

/**
 * HuggingFace-compatible WordPiece tokenizer for Chinese BERT / MiniLM-class models.
 */
class BertWordPieceTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val unkToken: String,
    private val clsId: Long,
    private val sepId: Long,
    private val padId: Long,
    private val unkId: Long,
    private val maxLength: Int,
) {
    data class Encoded(
        val inputIds: LongArray,
        val attentionMask: LongArray,
    )

    fun encode(text: String): Encoded {
        val wordPieces = mutableListOf<String>()
        for (token in basicTokenize(text)) {
            wordPieces += wordPieceTokenize(token)
        }

        val ids = LongArray(maxLength) { padId }
        val mask = LongArray(maxLength) { 0L }
        ids[0] = clsId
        mask[0] = 1L

        var pos = 1
        val limit = minOf(wordPieces.size, maxLength - 2)
        for (i in 0 until limit) {
            ids[pos] = vocab[wordPieces[i]]?.toLong() ?: unkId
            mask[pos] = 1L
            pos++
        }
        ids[pos] = sepId
        mask[pos] = 1L

        return Encoded(ids, mask)
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = text.trim().lowercase()
        if (cleaned.isEmpty()) return emptyList()

        val spaced = tokenizeChineseChars(cleaned)
        return spaced.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun tokenizeChineseChars(text: String): String {
        val builder = StringBuilder()
        for (ch in text) {
            when {
                isCjk(ch) || isPunctuation(ch) -> {
                    if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
                    builder.append(ch).append(' ')
                }
                ch.isWhitespace() -> builder.append(' ')
                else -> builder.append(ch)
            }
        }
        return builder.toString().trim()
    }

    private fun wordPieceTokenize(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        if (vocab.containsKey(token)) return listOf(token)

        val output = mutableListOf<String>()
        var start = 0
        val chars = token.toCharArray()
        while (start < chars.size) {
            var end = chars.size
            var found: String? = null
            while (start < end) {
                var piece = chars.sliceArray(start until end).concatToString()
                if (start > 0) piece = "##$piece"
                if (vocab.containsKey(piece)) {
                    found = piece
                    break
                }
                end--
            }
            if (found == null) {
                output += unkToken
                break
            }
            output += found
            start = end
        }
        return output
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0x20000..0x2A6DF ||
            code in 0x2A700..0x2B73F ||
            code in 0x2B740..0x2B81F ||
            code in 0x2B820..0x2CEAF ||
            code in 0xF900..0xFAFF ||
            code in 0x2F800..0x2FA1F
    }

    private fun isPunctuation(ch: Char): Boolean {
        val code = ch.code
        return (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) ||
            (code in 0x3000..0x303F) || (code in 0xFF00..0xFFEF)
    }

    companion object {
        fun fromVocabLines(
            vocabLines: List<String>,
            unkToken: String,
            clsId: Long,
            sepId: Long,
            padId: Long,
            unkId: Long,
            maxLength: Int,
        ): BertWordPieceTokenizer {
            val vocab = mutableMapOf<String, Int>()
            vocabLines.forEachIndexed { index, token ->
                val trimmed = token.trim()
                if (trimmed.isNotEmpty()) {
                    vocab[trimmed] = index
                }
            }
            return BertWordPieceTokenizer(vocab, unkToken, clsId, sepId, padId, unkId, maxLength)
        }
    }
}
