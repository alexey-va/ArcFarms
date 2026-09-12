package ru.ruscrafting.farms.paper.mine.index

import ru.ruscrafting.farms.domain.worksite.WorksitePosition
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Compact, versioned representation of the mine targets belonging to one chunk. */
internal object MineIndexCodec {
    private const val MAGIC = 0x4D49 // ASCII "MI"
    private const val VERSION = 1
    private const val HEADER_BYTES = 8 // magic, version, flags, entry count
    private const val ENTRY_BYTES = Int.SIZE_BYTES
    private const val MAX_TARGETS_PER_CHUNK = 250_000
    private const val MAX_LEGACY_BYTES = 8 * 1024 * 1024

    // Eight local-coordinate bits + thirteen Y bits + eleven explicit role bits = one Int.
    private const val Y_MIN = -4_096
    private const val Y_MAX = 4_095
    private const val Y_MASK = 0x1FFF
    private const val Y_SIGN_BIT = 0x1000
    private const val Y_SIGN_EXTENSION = -0x2000
    private const val ROLE_SHIFT = 21
    private const val ROLE_MASK = 0x7FF

    private val roleBits = linkedMapOf(
        MineAnchorRole.MINEABLE to (1 shl 0),
        MineAnchorRole.PROSPECT to (1 shl 1),
        MineAnchorRole.SUPPORT to (1 shl 2),
        MineAnchorRole.RAIL to (1 shl 3),
        MineAnchorRole.VENT to (1 shl 4),
        MineAnchorRole.PUMP to (1 shl 5),
        MineAnchorRole.LAMP to (1 shl 6),
        MineAnchorRole.CRYSTAL to (1 shl 7),
        MineAnchorRole.NEST to (1 shl 8),
        MineAnchorRole.POWER to (1 shl 9),
        MineAnchorRole.MINER to (1 shl 10),
    )

    private val rolesByMask = Array(ROLE_MASK + 1) { mask ->
        roleBits.entries.filter { entry -> mask and entry.value != 0 }
            .mapTo(linkedSetOf()) { it.key }
    }

    /** Encodes one entry per position; callers must coalesce roles for duplicate positions. */
    fun encode(chunkX: Int, chunkZ: Int, targets: Collection<MineIndexedTarget>): ByteArray {
        require(targets.size <= MAX_TARGETS_PER_CHUNK) {
            "Too many mine index targets in one chunk"
        }
        val positions = HashSet<WorksitePosition>(targets.size)
        targets.forEach { target ->
            validatePosition(target.position, chunkX, chunkZ)
            require(positions.add(target.position)) {
                "Mine index contains duplicate positions in one chunk"
            }
            roleMask(target.roles)
        }

        val output = ByteBuffer.allocate(HEADER_BYTES + targets.size * ENTRY_BYTES).order(ByteOrder.BIG_ENDIAN)
        output.putShort(MAGIC.toShort())
        output.put(VERSION.toByte())
        output.put(0) // flags reserved for future versions
        output.putInt(targets.size)
        targets.forEach { target ->
            val p = target.position
            val localX = p.x and 0xF
            val localZ = p.z and 0xF
            val packedY = p.y and Y_MASK
            val packed = localX or
                (localZ shl 4) or
                (packedY shl 8) or
                (roleMask(target.roles) shl ROLE_SHIFT)
            output.putInt(packed)
        }
        return output.array()
    }

    /**
     * Decodes the binary format, or the transitional UTF-8 text BYTE_ARRAY written by
     * the previous index implementation. A binary header is authoritative, so an unknown
     * binary version is rejected instead of being mistaken for legacy text.
     */
    fun decode(world: String, chunkX: Int, chunkZ: Int, bytes: ByteArray): List<MineIndexedTarget> {
        require(bytes.size <= HEADER_BYTES + MAX_TARGETS_PER_CHUNK * ENTRY_BYTES || !hasBinaryMagic(bytes)) {
            "Mine index binary payload is too large"
        }
        return if (hasBinaryMagic(bytes)) {
            decodeBinary(world, chunkX, chunkZ, bytes)
        } else {
            require(bytes.size <= MAX_LEGACY_BYTES) { "Mine index legacy payload is too large" }
            decodeLegacy(world, chunkX, chunkZ, decodeUtf8(bytes))
        }
    }

    /** Returns true only for a complete header supported by this codec version. */
    fun isCompact(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES) return false
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return (header.short.toInt() and 0xFFFF) == MAGIC &&
            (header.get().toInt() and 0xFF) == VERSION &&
            header.get().toInt() == 0
    }

    fun decodeLegacy(world: String, chunkX: Int, chunkZ: Int, encoded: String): List<MineIndexedTarget> {
        require(encoded.length <= MAX_LEGACY_BYTES) { "Mine index legacy payload is too large" }
        if (encoded.isBlank()) return emptyList()

        val result = ArrayList<MineIndexedTarget>()
        val positions = HashSet<WorksitePosition>()
        var start = 0
        while (start <= encoded.length) {
            val separator = encoded.indexOf(';', start)
            val end = if (separator == -1) encoded.length else separator
            if (end > start) {
                require(result.size < MAX_TARGETS_PER_CHUNK) {
                    "Too many mine index targets in one chunk"
                }
                val target = decodeLegacyEntry(world, chunkX, chunkZ, encoded.substring(start, end))
                require(positions.add(target.position)) {
                    "Mine index contains duplicate positions in one chunk"
                }
                result += target
            }
            if (separator == -1) break
            start = separator + 1
        }
        return result
    }

    private fun decodeBinary(world: String, chunkX: Int, chunkZ: Int, bytes: ByteArray): List<MineIndexedTarget> {
        require(bytes.size >= HEADER_BYTES) { "Truncated mine index header" }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require((input.short.toInt() and 0xFFFF) == MAGIC) { "Invalid mine index magic" }
        require((input.get().toInt() and 0xFF) == VERSION) { "Unsupported mine index version" }
        require(input.get().toInt() == 0) { "Unsupported mine index flags" }
        val count = input.int
        require(count in 0..MAX_TARGETS_PER_CHUNK) { "Invalid mine index target count" }
        val expectedBytes = HEADER_BYTES.toLong() + count.toLong() * ENTRY_BYTES
        require(expectedBytes == bytes.size.toLong()) { "Malformed mine index payload length" }

        val result = ArrayList<MineIndexedTarget>(count)
        val positions = HashSet<WorksitePosition>(count)
        repeat(count) {
            val packed = input.int
            val localX = packed and 0xF
            val localZ = (packed ushr 4) and 0xF
            val packedY = (packed ushr 8) and Y_MASK
            val y = if (packedY and Y_SIGN_BIT != 0) packedY or Y_SIGN_EXTENSION else packedY
            require(y in Y_MIN..Y_MAX) { "Mine index Y is outside the codec range" }
            val mask = (packed ushr ROLE_SHIFT) and ROLE_MASK
            val roles = roles(mask)
            val position = WorksitePosition(
                world,
                chunkOrigin(chunkX) + localX,
                y,
                chunkOrigin(chunkZ) + localZ,
            )
            validatePosition(position, chunkX, chunkZ)
            require(positions.add(position)) { "Mine index contains duplicate positions in one chunk" }
            result += MineIndexedTarget(position, roles)
        }
        return result
    }

    private fun decodeLegacyEntry(world: String, chunkX: Int, chunkZ: Int, entry: String): MineIndexedTarget {
        val parts = entry.split(',', limit = 4)
        require(parts.size == 4) { "Malformed legacy mine index entry" }
        val x = parts[0].toIntOrNull() ?: throw IllegalArgumentException("Invalid legacy mine index X")
        val y = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid legacy mine index Y")
        val z = parts[2].toIntOrNull() ?: throw IllegalArgumentException("Invalid legacy mine index Z")
        val position = WorksitePosition(world, x, y, z)
        validatePosition(position, chunkX, chunkZ)
        val roleNames = parts[3].split('+')
        require(roleNames.isNotEmpty() && roleNames.none { it.isBlank() }) {
            "Legacy mine index entry has no roles"
        }
        val parsedRoles = roleNames.map { roleName ->
            runCatching { MineAnchorRole.valueOf(roleName) }.getOrElse {
                throw IllegalArgumentException("Unknown legacy mine index role: $roleName", it)
            }
        }.toSet()
        roleMask(parsedRoles)
        return MineIndexedTarget(position, parsedRoles)
    }

    private fun validatePosition(position: WorksitePosition, chunkX: Int, chunkZ: Int) {
        require(position.x shr 4 == chunkX && position.z shr 4 == chunkZ) {
            "Mine index target belongs to another chunk"
        }
        require(position.y in Y_MIN..Y_MAX) { "Mine index Y is outside the codec range" }
    }

    private fun chunkOrigin(chunk: Int): Int {
        val origin = chunk.toLong() * 16L
        require(origin in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "Mine index chunk coordinate is outside the integer world range"
        }
        return origin.toInt()
    }

    private fun roleMask(roles: Set<MineAnchorRole>): Int {
        require(roles.isNotEmpty()) { "Mine index target has no roles" }
        var mask = 0
        roles.forEach { role -> mask = mask or (roleBits[role] ?: error("Unmapped mine anchor role: $role")) }
        return mask
    }

    private fun roles(mask: Int): Set<MineAnchorRole> {
        require(mask != 0 && mask and ROLE_MASK == mask) { "Invalid mine index role mask" }
        return rolesByMask[mask].also { require(it.size == Integer.bitCount(mask)) { "Invalid mine index role mask" } }
    }

    private fun hasBinaryMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == (MAGIC ushr 8) &&
            (bytes[1].toInt() and 0xFF) == (MAGIC and 0xFF)

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw IllegalArgumentException("Malformed UTF-8 mine index payload", error)
    }
}
