# 构建

环境：JDK 21、Android SDK 36、Node.js 22 或更高版本。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintRelease
node --test app/tests/*.test.cjs
```

在 `local.properties` 中配置本机的 `sdk.dir`，或设置 `ANDROID_HOME`。

## 正式签名

将签名文件和以下属性文件保存在仓库之外，通过环境变量指定属性文件：

```properties
storeFile=/absolute/path/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

```sh
ROUTE_SIGNING_PROPERTIES=/absolute/path/signing.properties ./gradlew :app:assembleRelease
```

不提供签名配置时，release 产物不签名。正式版本更新须沿用原签名。

## GitHub Releases

推送 `v` 开头、与 `versionName` 一致的版本标签会运行测试并发布签名 APK、校验文件和证书信息。仓库 Actions Secrets：

- `ANDROID_KEYSTORE_BASE64`：正式签名文件的 Base64。
- `ANDROID_KEYSTORE_PASSWORD`：签名文件密码。
- `ANDROID_KEY_ALIAS`：签名别名。
- `ANDROID_KEY_PASSWORD`：私钥密码。

签名材料仅在发布任务的临时目录中恢复，任务结束后删除。腾讯地图 Key 不参与构建；每位用户在应用内自行配置。

## 兼容性验证

腾讯地图 SDK 5.6.0 的原生库存在 16 KB 内存页对齐警告，16 KB 页设备尚未验证。
