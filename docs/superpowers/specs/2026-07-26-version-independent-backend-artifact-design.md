# 后端部署产物动态识别设计

## 背景

`armada-api/pom.xml` 已从 `1.0.0-SNAPSHOT` 升级到 `1.0.2-SNAPSHOT`，Maven 实际生成
`armada-api-1.0.2-SNAPSHOT.jar`。测试环境部署、Windows 部署、生产离线打包和后端 Dockerfile 仍固定引用
`armada-api-1.0.0-SNAPSHOT.jar`，因此直接部署会在 Maven 构建成功后因找不到旧文件名而中止。

## 目标

- 部署脚本不再依赖 POM 中的具体版本号。
- Maven 构建后从 `target` 识别唯一的可执行 JAR。
- 上传到测试环境和复制到 Docker 构建上下文时统一使用稳定暂存名 `armada-api-deploy.jar`。
- 无候选或存在多个候选时立即失败，不猜测产物。
- 同时覆盖 macOS/Linux 测试部署、WSL/Windows 测试部署和生产离线打包。

## 方案

新增 `armada-deploy/lib/artifact.sh`，提供 `armada_resolve_backend_jar <target-dir>`：

1. 使用 POSIX 兼容的 `target/*.jar` glob，仅扫描目标目录第一层普通文件。
2. `.jar.original` 不匹配 `*.jar`，不会被当作可执行产物。
3. 候选恰好一个时输出绝对路径。
4. 候选为零或多于一个时返回非零并输出不含敏感信息的错误。

各部署脚本在 `mvn clean package` 成功后调用该函数，把返回值保存到 `JAR_PATH`。同步或准备 Docker 上下文时，
目标文件统一命名为 `armada-api-deploy.jar`；`backend.prebuilt.Dockerfile` 只复制该稳定名称。

不使用 `mvn help:evaluate`，避免额外插件解析和输出清洗；不把版本号重新硬编码成 `1.0.2`，避免下一次升级再次失效。

## 验证

- Shell 单测覆盖单一版本化 JAR、零候选和多候选。
- 部署/生产打包脚本测试断言稳定暂存名且不存在 `armada-api-<版本>-SNAPSHOT.jar` 硬编码。
- 执行 Shell 语法检查、部署脚本测试和生产打包脚本测试。
- 使用当前 `1.0.2-SNAPSHOT` POM 实际执行 Maven 构建并解析产物。
- 部署 test1 后验证容器稳定、API 健康检查通过，并核对容器内运行 JAR。

## 范围

只调整后端构建产物的发现和暂存，不修改业务接口、数据库、前端或协议层。部署范围为第一套测试环境后端。
