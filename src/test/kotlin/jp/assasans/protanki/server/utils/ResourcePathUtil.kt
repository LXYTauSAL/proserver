import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ResourcePathUtil {

  /**
   * 根据资源 id 和 version 计算目录片段（5 段，都是八进制字符串）
   * 例如: id = 6338, version = 1 -> ["0", "0", "30", "302", "1"]
   */
  fun encodeIdToSegments(id: Int, version: Int): List<String> {
    require(id >= 0) { "id must be non-negative" }
    require(version >= 0) { "version must be non-negative" }

    val b3 = (id ushr 24) and 0xFF
    val b2 = (id ushr 16) and 0xFF
    val b1 = (id ushr 8) and 0xFF
    val b0 = id and 0xFF

    return listOf(b3, b2, b1, b0, version).map { it.toString(8) } // 转成八进制字符串
  }

  /**
   * 在 resources 根目录下的相对路径
   * 例如: id = 6338, version = 1 -> "resources/0/0/30/302/1"
   */
  fun relativePath(id: Int, version: Int): String {
    val segments = encodeIdToSegments(id, version)
    return "resources/" + segments.joinToString("/")
  }

  /**
   * 返回本地文件系统上的绝对路径（你传入 resources 根目录）
   *
   * @param resourcesRoot 例如: "D:/Games/ProTanki/resources"
   */
  fun absolutePath(resourcesRoot: Path, id: Int, version: Int): Path {
    val segments = encodeIdToSegments(id, version)
    return segments.fold(resourcesRoot) { acc, seg -> acc.resolve(seg) }
  }

  /**
   * 简单检查这个 id/version 对应目录是否存在，以及列一下里面的文件
   */
  fun debugPrintResources(resourcesRoot: Path, id: Int, version: Int) {
    val path = absolutePath(resourcesRoot, id, version)
    println("ID=$id, version=$version")
    println("  segments = ${encodeIdToSegments(id, version)}")
    println("  path     = $path")

    if (!Files.exists(path)) {
      println("  [!] 目录不存在")
      return
    }

    if (!Files.isDirectory(path)) {
      println("  [!] 这个路径不是目录")
      return
    }

    println("  files:")
    Files.list(path).use { stream ->
      stream.forEach { p ->
        println("    - ${p.fileName}")
      }
    }
  }
}
