import java.nio.file.Paths

fun main() {
  // 把这里改成你解压 resources.zip 后的根目录
  val resourcesRoot = Paths.get("C:/Users/Luo13/Downloads/TankiOnline/TankiOnline/resources")

  // 比如测试 Dictator M1: object3ds = 6338, version = 1
  val id = 1000076
  val version = 1

  ResourcePathUtil.debugPrintResources(resourcesRoot, id, version)

//  val id2 = 867345
//  val version2 = 1
//
//  ResourcePathUtil.debugPrintResources(resourcesRoot, id2, version2)
//
//  val id3 = 467894
//  val version3 = 2
//
//  ResourcePathUtil.debugPrintResources(resourcesRoot, id3, version3)
//
//  val id4 = 548743
//  val version4 = 1
//
//  ResourcePathUtil.debugPrintResources(resourcesRoot, id4, version4)

}
