<div align="center">

# iceScan · 冰扫

**把手机变成一台随身扫描仪 —— 拍一张，自动找出纸的四边、拉直、去阴影，多页拼成一个 PDF，全程不联网**

[![Release](https://img.shields.io/github/v/release/ice-wocker/iceScan?color=38BDF8&label=Release)](https://github.com/ice-wocker/iceScan/releases)
[![CI](https://github.com/ice-wocker/iceScan/actions/workflows/android.yml/badge.svg)](https://github.com/ice-wocker/iceScan/actions/workflows/android.yml)
[![Stars](https://img.shields.io/github/stars/ice-wocker/iceScan?color=38BDF8)](https://github.com/ice-wocker/iceScan/stargazers)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?logo=android&logoColor=white)](#)
[![零依赖](https://img.shields.io/badge/%E4%BE%9D%E8%B5%96-0%20%E4%B8%AA%E7%AC%AC%E4%B8%89%E6%96%B9%E5%BA%93-4B5563)](#)
[![无网络权限](https://img.shields.io/badge/%E6%9D%83%E9%99%90-%E4%B8%80%E4%B8%AA%E9%83%BD%E4%B8%8D%E7%94%B3%E8%AF%B7-22D3EE)](#隐私)

[下载安装](#下载安装) · [功能](#功能) · [怎么用](#怎么用) · [技术亮点](#技术亮点) · [隐私](#隐私) · [已知限制](#已知限制) · [构建](#构建)

</div>

---

一个干净的 Android 文档扫描 App：**不引任何第三方库、不申请任何权限、不联网**。拍一张纸，它会自动找出纸张四边并把拍歪的
画面拉成正视；光线不均、一半在阴影里，也能压平；最后把多页合成为一个 PDF 分享出去。

最直白的一条：**这个 App 的权限列表是空的**。拍照交给系统相机、从相册选图走系统文件选择器、导出的 PDF 用自己的
`ContentProvider` 递出去——三件事都不需要权限。照片和文字没有任何技术路径离开这台设备。

> **当前版本 v0.1.0** · 纯 Java 实现 · 零第三方依赖 · 不需要联网 · 不需要存储权限 · 不需要账号

---

## 功能

<table>
<tr><td width="130"><b>自动找边</b></td><td>

拍完（或从相册选完）立刻自动猜出纸张的四个角，拖角微调即可。找不到明显的纸时退成整幅画面，等你手动拖——
**不会拦着你用**。

</td></tr>
<tr><td><b>四角编辑</b></td><td>

四角可拖，同一侧会弹出**放大镜**，手指再粗也能对到纸的边缘上。拖过头会把四边形拧成自交或压成一条线，
这时直接「顶住」不让拖——因为那样解不出正确的变换。

</td></tr>
<tr><td><b>透视拉直</b></td><td>

由四组对应点解出单应矩阵，对输出图的每个像素**反查**它在原图的位置再双线性取色，斜着拍也能拉成正视，
而且不会出现空洞。

</td></tr>
<tr><td><b>四种效果</b></td><td>

原图 / 彩色 / 灰度 / 黑白，**实时预览**，改档位马上能看到结果。彩色档用「像素 × 全局均值 ÷ 局部背景」做光照归一化，
同一张纸上一半被灯照、一半在阴影里，压平后亮度一致；黑白档用 Bradley 自适应阈值，靠积分图做到 O(像素数)，
阴影区不会被整片染黑。

</td></tr>
<tr><td><b>批量导入</b></td><td>

从相册一次多选十几张，**每张自己找边、拉直、去阴影**，直接落成一个多页文档。多选时不逐张让你拖角——
十几张能拖到崩溃，找边不准的再进文档删掉重拍即可。

</td></tr>
<tr><td><b>多页文档</b></td><td>

一个文档就是一个多页扫描件：翻看、加页、删页、重命名、整份删除。缩略图后台解码 + LRU 缓存，
列表滚动不串图、不卡顿。

</td></tr>
<tr><td><b>导出 PDF</b></td><td>

自写了一个不到 200 行的 PDF 生成器：PDF 的 `/DCTDecode` 过滤器就是 JPEG，所以**直接把已经写好的 JPEG 字节贴进流里**，
不重编码、不引库、不掉画质。页面统一按 A4 居中等比排版。

</td></tr>
<tr><td><b>分享</b></td><td>

导出的 PDF 通过自己的 `ContentProvider` 交给别的 App 打开或分享，只把「读这一个 URI」的权利借出去，
不开放整个目录。

</td></tr>
</table>

## 怎么用

1. **扫描文档**：进扫描页 → 拍一张（或从相册导入）→ 调整页自动找边 → 拖角微调 / 选效果 → 完成。
2. **继续加页**：扫描页下方会攒出缩略图条，接着拍就行。
3. **导入图片**：首页 → 导入图片 → 一次多选 → 自动批量处理成一个文档。
4. **导出**：进文档页 → 导出 PDF → 交给微信 / 邮件 / 云盘。
5. **管理**：首页长按文档可重命名 / 删除；文档页右上角「更多」可分享 / 重命名 / 删除。

## 技术亮点

<table>
<tr><td width="150"><b>零第三方依赖</b></td><td>

`app/build.gradle` 里只有一行 `testImplementation junit`——运行时依赖为 **0**。没有 AndroidX、没有 OkHttp、
没有 OpenCV，连 `FileProvider` 都是手写的（因为整个 App 不引 AndroidX，而这件事只有几十行）。
APK 体积小、启动快，也不会被上游库的供应链问题波及。

</td></tr>
<tr><td><b>零权限</b></td><td>

相机用 `ACTION_IMAGE_CAPTURE` 把活交给系统相机 App，照片通过自己的 `ContentProvider` 写回来，所以不需要
`CAMERA` 权限；相册用 `ACTION_OPEN_DOCUMENT` 由系统文件选择器单次授权，所以不需要存储权限。装完可以自己去
「设置 → 应用 → iceScan → 权限」核对：列表是空的。

</td></tr>
<tr><td><b>内存自适应</b></td><td>

扫描流水线里同时活着好几个「每像素 4 字节」的整型数组（灰度图、背景、积分图…），分辨率写死就会在中低端机上
OOM。所以处理分辨率**按堆上限自适应**：堆够大用 2200（A4 合 168 DPI），小内存机器退到 1400——宁可锐度低一点，
也不能识别到一半崩掉。解码阶段就按 2 的幂次降采样，一张 4000×3000 的照片最多只分配一次。

</td></tr>
<tr><td><b>算法都自己写</b></td><td>

Otsu 阈值、积分图 box blur、连通域标记、Bradley 自适应阈值、高斯消元解单应矩阵、区域平均降采样、PDF 交叉引用表——
全部手写，全部是纯算术。好处是**这些类不碰任何 Android API**，能在普通 JVM 上直接跑单元测试，不用起模拟器。

</td></tr>
<tr><td><b>可测</b></td><td>

`./gradlew testDebugUnitTest` 跑 30+ 个用例，覆盖几何换算、透视、增强、找边、文档存储与 PDF 结构，
几秒钟出结果。CI 里连 lint 一起卡，红了就合不进去。

</td></tr>
</table>

### 代码结构

```
app/src/main/java/com/ice/scan/
├── data/       Doc / Page / DocStore        文档模型与磁盘仓库（纯 java.io，可直接测）
├── img/        AutoCrop / Perspective / Homography / Enhance / Resize / Quad / PdfWriter
│                                            全部纯算术，无 Android 依赖
├── scan/       Pipeline                     找边 → 拉直 → 增强 → 落盘 的流水线
├── share/      ScanFileProvider             手写 FileProvider：相机写入 + PDF 分享
├── ui/         Main / Capture / Crop / Viewer / QuadView / ThumbLoader / BaseActivity
└── util/       Bitmaps / Storage            解码编码、原子写
```

## 隐私

- **不申请任何权限**：没有 INTERNET、没有 CAMERA、没有存储权限。清单里能自己数出来。
- **不联网**：`AndroidManifest.xml` 里根本没有 `INTERNET` 权限，代码里也没有任何网络调用。
- **照片只在本机**：扫描件写在应用私有目录（`getFilesDir()`），别的 App 看不到，卸载即清空。
- **没有账号、没有统计、没有上报**：没有登录，没有埋点，没有崩溃上报。

## 已知限制

- **自动找边**的假设是「纸是画面里最亮的那一大块」。旋转 45°、深色纸上写字、或者背景比纸还亮时会猜错——
  所以四角始终可拖，猜错也能自己拖回来。
- **没有手电筒补光、没有连拍纠偏**，靠系统相机拍；光线均匀、别用手挡效果最好。
- 追求的是「小、快、干净」。要 OCR（把扫描件变成可编辑文字）不在这个项目的范围内。

## 下载安装

去 [Releases](https://github.com/ice-wocker/iceScan/releases) 下载最新的 `app-release.apk`，直接安装。
Release 包是**正式签名**的，可直接覆盖安装升级。

## 构建

需要 JDK 17、Android SDK（platform 36 / build-tools 36.0.0）。没有 NDK、没有原生库，纯 Java。

```bash
./gradlew testDebugUnitTest   # 单元测试（纯 JVM，秒出）
./gradlew lintDebug           # 静态检查
./gradlew assembleDebug       # 产出 app/build/outputs/apk/debug/app-debug.apk
```

正式发布需要签名。在仓库根目录放一个 `keystore.properties`（已被 `.gitignore` 忽略）：

```properties
storeFile=../release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

没有这个文件时 `assembleRelease` 会退回 debug 签名，仅供本地验证。

---

<div align="center">

**用最少的代码，做最干净的一件事。**

</div>